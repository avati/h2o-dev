package hex.schemas;

import hex.kmeans.KMeansModel;
import water.H2O;
import water.Key;
import water.api.*;
import water.api.Handler;
import water.api.ModelBase;
//import water.util.DocGen.HTML;

public class KMeansModelV2 extends ModelBase<KMeansModel, KMeansModelV2> {
  // Input fields
  @API(help="KMeans Model to inspect",required=true)
  Key key;

  // Output fields
  @API(help="Clusters[K][features]")
  double[/*K*/][/*features*/] clusters;

  @API(help="Rows[K]")
  long[/*K*/] rows;

  @API(help="Mean Square Error per cluster")
  public double[/*K*/] mses;   // Per-cluster MSE, variance

  @API(help="Mean Square Error")
  public double mse;           // Total MSE, variance

  @API(help="Iterations executed")
  public double iters;

  //==========================
  // Customer adapters Go Here

  // Version&Schema-specific filling into the handler
  @Override public KMeansModel createImpl() {
    return (KMeansModel) this._model;
  }

  // Version&Schema-specific filling from the handler
  @Override public KMeansModelV2 fillFromImpl( KMeansModel kmm ) {
    // if( !(h instanceof InspectHandler) ) throw H2O.unimpl();
    // InspectHandler ih = (InspectHandler)h;
    // KMeansModel kmm = ih._val.get();
    clusters = kmm._clusters;
    rows = kmm._rows;
    mses = kmm._mses;
    mse = kmm._mse;
    iters = kmm._iters;
    return this;
  }
}
