package hex.schemas;

import hex.kmeans.KMeans;
import hex.kmeans.KMeansModel.KMeansParameters;
import water.H2O;
import water.Iced;
import water.Job;
import water.api.Handler;

public class KMeansHandler extends Handler<KMeansParameters,KMeansV2> {
  @Override protected int min_ver() { return 2; }
  @Override protected int max_ver() { return Integer.MAX_VALUE; }

  public KMeansHandler() {}
  // TODO: move this into a new ModelBuilderHandler superclass
  // TODO: also add a score method in the new ModelBuilderHandler superclass
  public KMeansV2 train(int version, KMeansParameters parms) {
    assert parms != null; /* impl._job = */
    Job job = new KMeans(parms);
    KMeansV2 schema = schema(version); // TODO: superclass!
    schema.job = job._key;
    return schema;
  }
  @Override protected KMeansV2 schema(int version) { return new KMeansV2(); }
  @Override public void compute2() { throw H2O.fail(); }
}
