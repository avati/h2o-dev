package hex.example;

import hex.schemas.ExampleModelV2;
import water.*;
import water.api.Schema;
import water.fvec.Frame;
import water.fvec.Chunk;

public class ExampleModel extends SupervisedModel<ExampleModel,ExampleModel.ExampleParameters> {

  public static class ExampleParameters extends Model.Parameters<ExampleModel,ExampleModel.ExampleParameters> {
    public Key _src;              // Frame being clustered
    public int _max_iters;        // Max iterations
  }

  // Iterations executed
  public int _iters;

  ExampleModel( Key selfKey, Frame fr, ExampleParameters parms) {
    super(selfKey,fr,parms,null);
  }

  // Default publically visible Schema is V2
  @Override public Schema schema() { return new ExampleModelV2(); }

  @Override protected float[] score0(double data[/*ncols*/], float preds[/*nclasses+1*/]) {  
    throw H2O.unimpl();
  }

  @Override protected String errStr() { throw H2O.unimpl(); }
  @Override public ModelCategory getModelCategory() { return Model.ModelCategory.Clustering; }
}
