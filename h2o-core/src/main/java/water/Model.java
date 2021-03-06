package water;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import water.fvec.*;
import water.util.ArrayUtils;
import water.util.Log;
import water.api.Schema;

/**
 * A Model models reality (hopefully).
 * A model can be used to 'score' a row, or a collection of rows on any
 * compatible dataset - meaning the row has all the columns with the same names
 * as used to build the mode.
 */
public abstract class Model<M extends Model<M,P>, P extends Model.Parameters<M,P>> extends Lockable<M> {
  Model( Key selfkey ) { super(selfkey); }

  /** Columns used in the model and are used to match up with scoring data
   *  columns.  The last name is the response column name. */
  protected String _names[];
  /** Returns number of input features */
  public int nfeatures() { return _names.length - 1; }

  /** Categorical/factor/enum mappings, per column.  Null for non-enum cols.
   *  The last column holds the response col enums.  */
  String _domains[][];

  public String[] allNames() { return _names; }
  public String responseName() { return   _names[  _names.length-1]; }
  public String[] classNames() { return _domains[_domains.length-1]; }
  public boolean isClassifier() { return classNames() != null ; }
  public int nclasses() {
    String cns[] = classNames();
    return cns==null ? 1 : cns.length;
  }

  public enum ModelCategory {
    Unknown,
    Binomial,
    Multinomial,
    Regression,
    Clustering
  }

  public ModelCategory getModelCategory() {
    return (isClassifier() ?
            (nclasses() > 2 ? ModelCategory.Multinomial : ModelCategory.Binomial) :
            ModelCategory.Regression);
  }

  // Model-specific parameter class.  Each model sub-class also supports a
  // sub-parameter list with model-specific parameters.  E.g. KMeansModel
  // extends Model & has a KMeansParameters extending Model.Parameters; sample
  // parameters include K, whether or not to normalize, max iterations and the
  // initial random seed.
  public abstract static class Parameters<M extends Model<M,P>, P extends Parameters<M,P>> extends Iced {
    /* This class has no fields and no code */
  }
  // TODO: make this an instance of a parameterized Parameters class. . .
  Parameters _parms;

  // Externally visible default schema
  // TODO: this is in the wrong layer: the internals should not know anything about the schemas!!!
  // This puts a reverse edge into the dependency graph.
  public abstract Schema schema();

  /** Constructor from frame: Strips out the Vecs to just the names needed
   *  to match columns later for future datasets.  */
  public Model( Key selfKey, Frame fr, Parameters parms ) {
    this(selfKey,fr.names(),fr.domains(),parms);
  }

  /** Full constructor */
  public Model( Key selfKey, String names[], String domains[][], Parameters parms ) {
    super(selfKey);
    if( domains == null ) domains=new String[names.length+1][];
    assert domains.length==names.length;
    assert names.length > 1;
    assert names[names.length-1] != null; // Have a valid response-column name?
    _names   = names;
    _domains = domains;
    assert parms != null;
    _parms = parms;
  }

  /** Bulk score for given <code>fr</code> frame.
   * The frame is always adapted to this model.
   *
   * @param fr frame to be scored
   * @return frame holding predicted values
   *
   * @see #score(Frame, boolean)
   */
  public final Frame score(Frame fr) {
    return score(fr, true);
  }
  /** Bulk score the frame <code>fr</code>, producing a Frame result; the 1st Vec is the
   *  predicted class, the remaining Vecs are the probability distributions.
   *  For Regression (single-class) models, the 1st and only Vec is the
   *  prediction value.
   *
   *  The flat <code>adapt</code>
   * @param fr frame which should be scored
   * @param adapt a flag enforcing an adaptation of <code>fr</code> to this model. If flag
   *        is <code>false</code> scoring code expect that <code>fr</code> is already adapted.
   * @return a new frame containing a predicted values. For classification it contains a column with
   *         prediction and distribution for all response classes. For regression it contains only
   *         one column with predicted values.
   */
  public final Frame score(Frame fr, boolean adapt) {
    int ridx = fr.find(responseName());
    if (ridx != -1) { // drop the response for scoring!
      fr = new Frame(fr);
      fr.remove(ridx);
    }
    // Adapt the Frame layout - returns adapted frame and frame containing only
    // newly created vectors
    Frame[] adaptFrms = adapt ? adapt(fr,false) : null;
    // Adapted frame containing all columns - mix of original vectors from fr
    // and newly created vectors serving as adaptors
    Frame adaptFrm = adapt ? adaptFrms[0] : fr;
    // Contains only newly created vectors. The frame eases deletion of these vectors.
    Frame onlyAdaptFrm = adapt ? adaptFrms[1] : null;
    // Invoke scoring
    Frame output = scoreImpl(adaptFrm);
    // Be nice to DKV and delete vectors which i created :-)
    if (adapt) onlyAdaptFrm.delete();
    return output;
  }

  /** Score already adapted frame.
   *
   * @param adaptFrm
   * @return
   */
  private Frame scoreImpl(Frame adaptFrm) {
    int ridx = adaptFrm.find(responseName());
    Vec vecs[] = adaptFrm.vecs();
    assert ridx == -1 : "Adapted frame should not contain response in scoring method!";
    assert nfeatures() == adaptFrm.numCols() : "Number of model features " + nfeatures() + " != number of test set columns: " + adaptFrm.numCols();
    assert vecs.length == _names.length-1 : "Scoring data set contains wrong number of columns: " + vecs.length  + " instead of " + (_names.length-1);

    // Create a new vector for response
    // If the model produces a classification/enum, copy the domain into the
    // result vector.
    Vec v = adaptFrm.anyVec().makeZero(classNames());
    adaptFrm.add("predict",v);
    if( nclasses() > 1 ) {
      String prefix = "";
      for( int c=0; c<nclasses(); c++ ) // if any class is the same as column name in frame, then prefix all classnames
        if (ArrayUtils.contains(adaptFrm._names, classNames()[c])) { prefix = "class_"; break; }
      for( int c=0; c<nclasses(); c++ )
        adaptFrm.add(prefix+classNames()[c],adaptFrm.anyVec().makeZero());
    }
    new MRTask() {
      @Override public void map( Chunk chks[] ) {
        double tmp [] = new double[_names.length];
        float preds[] = new float [nclasses()==1?1:nclasses()+1];
        int len = chks[0].len();
        for( int row=0; row<len; row++ ) {
          float p[] = score0(chks,row,tmp,preds);
          for( int c=0; c<preds.length; c++ )
            chks[_names.length-1+c].set0(row,p[c]);
        }
      }
    }.doAll(adaptFrm);
    // Return just the output columns
    int x=_names.length-1, y=adaptFrm.numCols();
    return adaptFrm.extractFrame(x, y);
  }

  /** Single row scoring, on a compatible Frame.  */
  public final float[] score( Frame fr, boolean exact, int row ) {
    double tmp[] = new double[fr.numCols()];
    for( int i=0; i<tmp.length; i++ )
      tmp[i] = fr.vecs()[i].at(row);
    return score(fr.names(),fr.domains(),exact,tmp);
  }

  /** Single row scoring, on a compatible set of data.  Fairly expensive to adapt. */
  public final float[] score( String names[], String domains[][], boolean exact, double row[] ) {
    return score(adapt(names,domains,exact),row,new float[nclasses()]);
  }

  /** Single row scoring, on a compatible set of data, given an adaption vector */
  public final float[] score( int map[][][], double row[], float[] preds ) {
    /*FIXME final int[][] colMap = map[map.length-1]; // Response column mapping is the last array
    assert colMap.length == _names.length-1 : " "+Arrays.toString(colMap)+" "+Arrays.toString(_names);
    double tmp[] = new double[colMap.length]; // The adapted data
    for( int i=0; i<colMap.length; i++ ) {
      // Column mapping, or NaN for missing columns
      double d = colMap[i]==-1 ? Double.NaN : row[colMap[i]];
      if( map[i] != null ) {    // Enum mapping
        int e = (int)d;
        if( e < 0 || e >= map[i].length ) d = Double.NaN; // User data is out of adapt range
        else {
          e = map[i][e];
          d = e==-1 ? Double.NaN : (double)e;
        }
      }
      tmp[i] = d;
    }
    return score0(tmp,preds);   // The results. */
    return null;
  }

  /** Build an adaption array.  The length is equal to the Model's vector length.
   *  Each inner 2D-array is a
   *  compressed domain map from data domains to model domains - or null for non-enum
   *  columns, or null for identity mappings.  The extra final int[] is the
   *  column mapping itself, mapping from model columns to data columns. or -1
   *  if missing.
   *  If 'exact' is true, will throw if there are:
   *    any columns in the model but not in the input set;
   *    any enums in the data that the model does not understand
   *    any enums returned by the model that the data does not have a mapping for.
   *  If 'exact' is false, these situations will use or return NA's instead.
   */
  private int[][][] adapt( String names[], String domains[][], boolean exact) {
    int maplen = names.length;
    int map[][][] = new int[maplen][][];
    // Make sure all are compatible
    for( int c=0; c<names.length;++c) {
            // Now do domain mapping
      String ms[] = _domains[c];  // Model enum
      String ds[] =  domains[c];  // Data  enum
      if( ms == ds ) { // Domains trivially equal?
      } else if( ms == null ) {
        throw new IllegalArgumentException("Incompatible column: '" + _names[c] + "', expected (trained on) numeric, was passed a categorical");
      } else if( ds == null ) {
        if( exact )
          throw new IllegalArgumentException("Incompatible column: '" + _names[c] + "', expected (trained on) categorical, was passed a numeric");
        throw H2O.unimpl();     // Attempt an asEnum?
      } else if( !Arrays.deepEquals(ms, ds) ) {
        map[c] = getDomainMapping(_names[c], ms, ds, exact);
      } // null mapping is equal to identity mapping
    }
    return map;
  }

  /** Build an adapted Frame from the given Frame. Useful for efficient bulk
   *  scoring of a new dataset to an existing model.  Same adaption as above,
   *  but expressed as a Frame instead of as an int[][]. The returned Frame
   *  does not have a response column.
   *  It returns a <b>two element array</b> containing an adapted frame and a
   *  frame which contains only vectors which where adapted (the purpose of the
   *  second frame is to delete all adapted vectors with deletion of the
   *  frame). */
  public Frame[] adapt( final Frame fr, boolean exact) {
    Frame vfr = new Frame(fr); // To avoid modification of original frame fr
    int ridx = vfr.find(_names[_names.length-1]);
    if(ridx != -1 && ridx != vfr._names.length-1){ // Unify frame - put response to the end
      String n = vfr._names[ridx];
      vfr.add(n,vfr.remove(ridx));
    }
    int n = ridx == -1?_names.length-1:_names.length;
    String [] names = Arrays.copyOf(_names, n);
    Frame  [] subVfr;
    // replace missing columns with NaNs (or 0s for DeepLearning with sparse data)
    // subVfr = vfr.subframe(names, (this instanceof DeepLearningModel && ((DeepLearningModel)this).get_params().sparse) ? 0 : Double.NaN);
    subVfr = vfr.subframe(names, Double.NaN);
    vfr = subVfr[0]; // extract only subframe but keep the rest for delete later
    Vec[] frvecs = vfr.vecs();
    boolean[] toEnum = new boolean[frvecs.length];
    if(!exact) for(int i = 0; i < n;++i)
      if(_domains[i] != null && !frvecs[i].isEnum()) {// if model expects domain but input frame does not have domain => switch vector to enum
        frvecs[i] = frvecs[i].toEnum();
        toEnum[i] = true;
      }
    int[][][] map = adapt(names,vfr.domains(),exact);
    assert map.length == names.length; // Be sure that adapt call above do not skip any column
    ArrayList<Vec> avecs = new ArrayList<>(); // adapted vectors
    ArrayList<String> anames = new ArrayList<>(); // names for adapted vector

    for( int c=0; c<map.length; c++ ) // Iterate over columns
      if(map[c] != null) { // Column needs adaptation
        Vec adaptedVec;
        if (toEnum[c]) { // Vector was flipped to column already, compose transformation
          adaptedVec = TransfVec.compose((TransfVec) frvecs[c], map[c], vfr.domains()[c], false);
        } else adaptedVec = frvecs[c].makeTransf(map[c], vfr.domains()[c]);
        avecs.add(frvecs[c] = adaptedVec);
        anames.add(names[c]); // Collect right names
      } else if (toEnum[c]) { // Vector was transformed to enum domain, but does not need adaptation we need to record it
        avecs.add(frvecs[c]);
        anames.add(names[c]);
      }
    // Fill trash bin by vectors which need to be deleted later by the caller.
    Frame vecTrash = new Frame(anames.toArray(new String[anames.size()]), avecs.toArray(new Vec[avecs.size()]));
//    if (subVfr[1]!=null) vecTrash.add(subVfr[1], true);
    return new Frame[] { new Frame(names,frvecs), vecTrash };
  }

  /** Returns a mapping between values of model domains (<code>modelDom</code>) and given column domain.
   *  @see #getDomainMapping(String, String[], String[], boolean) */
  public static int[][] getDomainMapping(String[] modelDom, String[] colDom, boolean exact) {
    return getDomainMapping(null, modelDom, colDom, exact);
  }

  /**
   * Returns a mapping for given column according to given <code>modelDom</code>.
   * In this case, <code>modelDom</code> is
   *
   * @param colName name of column which is mapped, can be null.
   * @param modelDom
   * @param logNonExactMapping
   * @return
   */
  public static int[][] getDomainMapping(String colName, String[] modelDom, String[] colDom, boolean logNonExactMapping) {
    int emap[] = new int[modelDom.length];
    boolean bmap[] = new boolean[modelDom.length];
    HashMap<String,Integer> md = new HashMap<>((int) ((colDom.length/0.75f)+1));
    for( int i = 0; i < colDom.length; i++) md.put(colDom[i], i);
    for( int i = 0; i < modelDom.length; i++) {
      Integer I = md.get(modelDom[i]);
      if (I == null && logNonExactMapping)
        Log.warn("Domain mapping: target domain contains the factor '"+modelDom[i]+"' which DOES NOT appear in input domain " + (colName!=null?"(column: " + colName+")":""));
      if (I!=null) {
        emap[i] = I;
        bmap[i] = true;
      }
    }
    if (logNonExactMapping) { // Inform about additional values in column domain which do not appear in model domain
      for (int i=0; i<colDom.length; i++) {
        boolean found = false;
        for (int anEmap : emap)
          if (anEmap == i) {
            found = true;
            break;
          }
        if (!found)
          Log.warn("Domain mapping: target domain DOES NOT contain the factor '"+colDom[i]+"' which appears in input domain "+ (colName!=null?"(column: " + colName+")":""));
      }
    }

    // produce packed values
    int[][] res = water.fvec.TransfVec.pack(emap, bmap);
    // Sort values in numeric order to support binary search in TransfVec
    water.fvec.TransfVec.sortWith(res[0], res[1]);
    return res;
  }

  /** Bulk scoring API for one row.  Chunks are all compatible with the model,
   *  and expect the last Chunks are for the final distribution and prediction.
   *  Default method is to just load the data into the tmp array, then call
   *  subclass scoring logic. */
  abstract protected float[] score0( Chunk chks[], int row_in_chunk, double[] tmp, float[] preds );

  /** Subclasses implement the scoring logic.  The data is pre-loaded into a
   *  re-used temp array, in the order the model expects.  The predictions are
   *  loaded into the re-used temp array, which is also returned.  */
  protected abstract float[] score0(double data[/*ncols*/], float preds[/*nclasses+1*/]);
  // Version where the user has just ponied-up an array of data to be scored.
  // Data must be in proper order.  Handy for JUnit tests.
  public double score(double [] data){ return ArrayUtils.maxIndex(score0(data, new float[nclasses()]));  }
}
