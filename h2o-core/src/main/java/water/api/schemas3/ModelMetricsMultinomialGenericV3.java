package water.api.schemas3;

import hex.ModelMetricsMultinomialGeneric;


public class ModelMetricsMultinomialGenericV3<I extends ModelMetricsMultinomialGeneric, S extends ModelMetricsMultinomialGenericV3<I, S>>
    extends ModelMetricsMultinomialV3<I, S> {

  @Override
  public S fillFromImpl(I modelMetrics) {
    super.fillFromImpl(modelMetrics);
    logloss = modelMetrics._logloss;
    r2 = modelMetrics.r2();

    if (modelMetrics._hit_ratio_table != null) {
      hit_ratio_table = new TwoDimTableV3(modelMetrics._hit_ratio_table);
    } 

    if (null != modelMetrics._confusion_matrix_table) {
      final ConfusionMatrixV3 convertedConfusionMatrix = new ConfusionMatrixV3();
      convertedConfusionMatrix.table = new TwoDimTableV3().fillFromImpl(modelMetrics._confusion_matrix_table);
      this.cm = convertedConfusionMatrix;
    }
    
    return (S)this;
  }
}
