package ai.h2o.automl;

import hex.*;
import water.*;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Frame;
import water.util.ArrayUtils;
import water.util.IcedHashMap;
import water.util.Log;
import water.util.TwoDimTable;

import java.util.*;

import static water.DKV.getGet;


/**
 * Utility to track all the models built for a given dataset type.
 * <p>
 * Note that if a new Leaderboard is made for the same project_name it'll
 * keep using the old model list, which allows us to run AutoML multiple
 * times and keep adding to the leaderboard.
 * <p>
 * The models are returned sorted by either an appropriate default metric
 * for the model category (auc, mean per class error, or mean residual deviance),
 * or by a metric that's set via #setMetricAndDirection.
 * <p>
 * TODO: make this robust against removal of models from the DKV.
 */
public class Leaderboard extends Lockable<Leaderboard> {
  /**
   * Identifier for models that should be grouped together in the leaderboard
   * (e.g., "airlines" and "iris").
   */
  private final String _project_name;

  /**
   * List of models for this leaderboard, sorted by metric so that the best is first,
   * according to the standard metric for the given model type.
   * <p>
   * Updated inside addModels().
   */
  private Key<Model>[] _modelKeys = new Key[0];

  /**
   * Leaderboard/test set ModelMetrics objects for the models.
   * <p>
   * Updated inside addModels().
   */
  private final transient IcedHashMap<Key<ModelMetrics>, ModelMetrics> _leaderboard_set_metrics = new IcedHashMap<>();

  /**
   * Sort metrics for the models in this leaderboard, in the same order as the models.
   * <p>
   * Updated inside addModels().
   */
  public double[] sort_metrics = new double[0];

  /**
   * Additional metrics for the models in this leaderboard, in the same order as the models
   * Regression metrics: rmse, mse, mae, and rmsle
   * Binomial metrics: logloss, mean_per_class_error, rmse, & mse
   * Multinomial metrics: logloss, mean_per_class_error, rmse, & mse
   * <p>
   * Updated inside addModels().
   */
  public double[] mean_residual_deviance = new double[0];
  public double[] rmse = new double[0];
  public double[] mse = new double[0];
  public double[] mae = new double[0];
  public double[] rmsle = new double[0];
  public double[] logloss = new double[0];
  public double[] auc = new double[0];
  public double[] mean_per_class_error = new double[0];

  /**
   * Metric used to sort this leaderboard.
   */
  String _sort_metric;

  /**
   * Other metrics reported in leaderboard
   * Regression metrics: rmse, mse, mae, and rmsle
   * Binomial metrics: logloss, mean_per_class_error, rmse, & mse
   * Multinomial metrics: logloss, mean_per_class_error, rmse, & mse
   */
  private String[] _other_metrics;

  /**
   * Metric direction used in the sort.
   */
  private boolean _sort_decreasing;

  /**
   * Have we set the sort_metric based on a model in the leadboard?
   */
  private boolean _sort_metric_autoset = false;

  /**
   * The eventLog attached to same AutoML instance as this Leaderboard object.
   */
  private final Key<EventLog> _eventLogKey;

  /**
   * Frame for which we return the metrics, by default.
   */
  private final Key<Frame> _leaderboardFrameKey;

  /**
   * Checksum for the Frame for which we return the metrics, by default.
   */
  private final long _leaderboardFrameChecksum;

  /**
   *
   */
  public Leaderboard(String project_name, EventLog eventLog, Frame leaderboardFrame, String sort_metric) {
    super(Key.make(idForProject(project_name)));
    this._project_name = project_name;
    this._eventLogKey = eventLog._key;
    this._leaderboardFrameKey = leaderboardFrame == null ? null : leaderboardFrame._key;
    this._leaderboardFrameChecksum = leaderboardFrame == null ? 0 : leaderboardFrame.checksum();
    this._sort_metric = sort_metric == null ? null : sort_metric.toLowerCase();
  }

  static Leaderboard getOrMake(String project_name, EventLog eventLog, Frame leaderboardFrame, String sort_metric) {
    Leaderboard leaderboard = DKV.getGet(Key.make(idForProject(project_name)));
    if (null != leaderboard) {
      if (leaderboardFrame != null && leaderboardFrame._key.equals(leaderboard._leaderboardFrameKey))
        throw new H2OIllegalArgumentException("Cannot use leaderboard "+project_name+" with a new leaderboard frame"
                +" (existing leaderboard frame: "+leaderboard._leaderboardFrameKey+").");
    } else {
      leaderboard = new Leaderboard(project_name, eventLog, leaderboardFrame, sort_metric);
    }
    DKV.put(leaderboard);
    return leaderboard;
  }

  public static String idForProject(String project_name) { return "AutoML_Leaderboard_" + project_name; }

  public String getProject() {
    return _project_name;
  }

  private EventLog eventLog() { return _eventLogKey.get(); }

  private void setMetricAndDirection(String metric, String[] otherMetrics, boolean sortDecreasing) {
    this._sort_metric = metric;
    this._other_metrics = otherMetrics;
    this._sort_decreasing = sortDecreasing;
    this._sort_metric_autoset = true;
  }

  private void setDefaultMetricAndDirection(Model m) {
    String[] metrics;
    if (m._output.isBinomialClassifier()) { //Binomial
      metrics = new String[]{"logloss", "mean_per_class_error", "rmse", "mse"};
      if(this._sort_metric == null) {
        this._sort_metric = "auc";
      }
    }
    else if (m._output.isMultinomialClassifier()) { //Multinomial
      metrics = new String[]{"logloss", "rmse", "mse"};
      if(this._sort_metric == null) {
        this._sort_metric = "mean_per_class_error";
      }
    }
    else { //Regression
      metrics = new String[]{"rmse", "mse", "mae", "rmsle"};
      if(this._sort_metric == null) {
        this._sort_metric = "mean_residual_deviance";
      }
    }
    boolean sortDecreasing = this._sort_metric.equals("auc");
    setMetricAndDirection(this._sort_metric, metrics, sortDecreasing);
  }

  /**
   * Add the given models to the leaderboard.
   * Note that to make this easier to use from Grid, which returns its models in random order,
   * we allow the caller to add the same model multiple times and we eliminate the duplicates here.
   * @param newModels
   */
  final void addModels(final Key<Model>[] newModels) {
    if (null == this._key)
      throw new H2OIllegalArgumentException("Can't add models to a Leaderboard which isn't in the DKV.");

    // This can happen if a grid or model build timed out:
    if (null == newModels || newModels.length == 0) {
      return;
    }

    write_lock(); //no job/key needed as currently the leaderboard instance can only be updated by its corresponding AutoML job (otherwise, would need to pass a job param to addModels)
    if (! this._sort_metric_autoset) {
      // lazily set to default for this model category
      setDefaultMetricAndDirection(newModels[0].get());
    }

    final Key<Model> newLeader[] = new Key[1]; // only set if there's a new leader
    final double newLeaderSortMetric[] = new double[1];

    final Key<Model>[] oldModelKeys = _modelKeys;
    final Key<Model> oldLeaderKey = (oldModelKeys == null || 0 == oldModelKeys.length) ? null : oldModelKeys[0];

    // eliminate duplicates
    Set<Key<Model>> uniques = new HashSet<>(oldModelKeys.length + newModels.length);
    uniques.addAll(Arrays.asList(oldModelKeys));
    uniques.addAll(Arrays.asList(newModels));
    _modelKeys = uniques.toArray(new Key[0]);

    Model model = null;
    Frame leaderboardFrame = leaderboardFrame();
    for (Key<Model> modelKey : _modelKeys) {
      model = modelKey.get();
      if (null == model) {
        eventLog().warn(EventLogEntry.Stage.ModelTraining, "Model in the leaderboard has unexpectedly been deleted from H2O: " + modelKey);
        continue;
      }

      // If leaderboardFrame is null, use default model metrics instead
      ModelMetrics mm;
      if (leaderboardFrame == null) {
        mm = ModelMetrics.defaultModelMetrics(model);
      } else {
        mm = ModelMetrics.getFromDKV(model, leaderboardFrame);
        if (mm == null) {
          //scores and magically stores the metrics where we're looking for it on the next line
          model.score(leaderboardFrame).delete();
          mm = ModelMetrics.getFromDKV(model, leaderboardFrame);
        }
      }
      if (mm != null) _leaderboard_set_metrics.putIfAbsent(mm._key, mm);
    }

    // Sort by metric on the leaderboard/test set or default model metrics.
    try {
      List<Key<Model>> modelsSorted;
      if (leaderboardFrame == null) {
        modelsSorted = ModelMetrics.sortModelsByMetric(_sort_metric, _sort_decreasing, Arrays.asList(_modelKeys));
      } else {
        modelsSorted = ModelMetrics.sortModelsByMetric(leaderboardFrame, _sort_metric, _sort_decreasing, Arrays.asList(_modelKeys));
      }
      _modelKeys = modelsSorted.toArray(new Key[0]);
    } catch (H2OIllegalArgumentException e) {
      Log.warn("ModelMetrics.sortModelsByMetric failed: " + e);
      throw e;
    }

    Model[] models = getModelsFromKeys(_modelKeys);
    sort_metrics = getMetrics(_sort_metric, models);
    if (model._output.isBinomialClassifier()) { // Binomial case
      auc = getMetrics("auc", models);
      logloss = getMetrics("logloss", models);
      mean_per_class_error = getMetrics("mean_per_class_error", models);
      rmse = getMetrics("rmse", models);
      mse = getMetrics("mse", models);
    } else if (model._output.isMultinomialClassifier()) { //Multinomial Case
      mean_per_class_error = getMetrics("mean_per_class_error", models);
      logloss = getMetrics("logloss",models);
      rmse = getMetrics("rmse", models);
      mse = getMetrics("mse", models);
    } else { //Regression Case
      mean_residual_deviance= getMetrics("mean_residual_deviance", models);
      rmse = getMetrics("rmse", models);
      mse = getMetrics("mse", models);
      mae = getMetrics("mae", models);
      rmsle = getMetrics("rmsle", models);
    }

    // If we're updated leader let this know so that it can notify the user
    // (outside the tatomic, since it can take a long time).
    if (oldLeaderKey == null || !oldLeaderKey.equals(_modelKeys[0])) {
      newLeader[0] = _modelKeys[0];
      newLeaderSortMetric[0] = sort_metrics[0];
    }
    update();
    unlock();

    // always
    if (null != newLeader[0]) {
      eventLog().info(EventLogEntry.Stage.ModelTraining,
              "New leader: " + newLeader[0] + ", " + _sort_metric + ": " + newLeaderSortMetric[0]);
    }
  } // addModels


  void addModel(final Key<Model> key) {
    if (null == key) return;

    Key<Model>keys[] = new Key[1];
    keys[0] = key;
    addModels(keys);
  }

  void addModel(final Model model) {
    if (null == model) return;

    Key<Model>keys[] = new Key[1];
    keys[0] = model._key;
    addModels(keys);
  }

  private static Model[] getModelsFromKeys(Key<Model>[] modelKeys) {
    Model[] models = new Model[modelKeys.length];
    int i = 0;
    for (Key<Model> modelKey : modelKeys)
      models[i++] = getGet(modelKey);
    return models;
  }

  /**
   * @return list of keys of models sorted by the default metric for the model category, fetched from the DKV
   */
  Key<Model>[] getModelKeys() {
    Leaderboard uptodate = DKV.getGet(this._key);
    return uptodate == null ? new Key[0] : uptodate._modelKeys;
  }

  /**
   * @return list of keys of models sorted by the given metric, fetched from the DKV
   */
  private Key<Model>[] sortModels(String metric, boolean decreasing) {
    Key<Model>[] models = getModelKeys();
    List<Key<Model>> newModelsSorted =
            ModelMetrics.sortModelsByMetric(metric, decreasing, Arrays.asList(models));
    return newModelsSorted.toArray(new Key[0]);
  }

  /**
   * @return list of models sorted by the default metric for the model category
   */
  Model[] getModels() {
    Key<Model>[] modelKeys = getModelKeys();
    if (modelKeys == null || 0 == modelKeys.length) return new Model[0];
    return getModelsFromKeys(modelKeys);
  }

  /**
   * @return list of models sorted by the given metric
   */
  Model[] getSortedModels(String metric, boolean decreasing) {
    Key<Model>[] modelKeys = sortModels(metric, decreasing);
    if (modelKeys == null || 0 == modelKeys.length) return new Model[0];
    return getModelsFromKeys(modelKeys);
  }

  Model getLeader() {
    Key<Model>[] modelKeys = getModelKeys();
    if (modelKeys == null || 0 == modelKeys.length) return null;
    return modelKeys[0].get();
  }

  /** Return the number of models in this Leaderboard. */
  int getModelCount() { return getModelKeys().length; }

  private double[] getMetrics(String metric, Model[] models) {
    double[] other_metrics = new double[models.length];
    int i = 0;
    Frame leaderboardFrame = leaderboardFrame();
    for (Model m : models) {
      // If leaderboard frame exists, get metrics from there
      if (leaderboardFrame != null) {
        other_metrics[i++] = ModelMetrics.getMetricFromModelMetric(
            _leaderboard_set_metrics.get(ModelMetrics.buildKey(m, leaderboardFrame)),
            metric
        );
      } else {
        // otherwise use default model metrics
        Key model_key = m._key;
        long model_checksum = m.checksum();
        ModelMetrics mm = ModelMetrics.defaultModelMetrics(m);
        other_metrics[i++] = ModelMetrics.getMetricFromModelMetric(
            _leaderboard_set_metrics.get(ModelMetrics.buildKey(model_key, model_checksum, mm.frame()._key, mm.frame().checksum())),
            metric
        );
      }
    }
    return other_metrics;
  }

  /**
   * Delete object and its dependencies from DKV, including models.
   */
  @Override
  protected Futures remove_impl(Futures fs, boolean cascade) {
    Log.debug("Cleaning up leaderboard from models "+Arrays.toString(_modelKeys));
    if (cascade) {
      for (Key<Model> m : _modelKeys) {
        Keyed.remove(m, fs, true);
      }
    }
    for (Key k : _leaderboard_set_metrics.keySet())
      Keyed.remove(k, fs, true);
    return super.remove_impl(fs, cascade);
  }

  private static double[] defaultMetricForModel(Model m) {
    return defaultMetricForModel(m, ModelMetrics.defaultModelMetrics(m));
  }

  private static double[] defaultMetricForModel(Model m, ModelMetrics mm) {
    if (m._output.isBinomialClassifier()) {
      return new double[] {
          ((ModelMetricsBinomial) mm).auc(),
          ((ModelMetricsBinomial) mm).logloss(),
          ((ModelMetricsBinomial) mm).mean_per_class_error(),
          mm.rmse(),
          mm.mse()
      };
    } else if (m._output.isMultinomialClassifier()) {
      return new double[] {
          ((ModelMetricsMultinomial) mm).mean_per_class_error(),
          ((ModelMetricsMultinomial) mm).logloss(),
          mm.rmse(),
          mm.mse()
      };
    } else if (m._output.isSupervised()) {
      return new double[] {
          ((ModelMetricsRegression)mm).mean_residual_deviance(),
          mm.rmse(),
          mm.mse(),
          ((ModelMetricsRegression) mm).mae(),
          ((ModelMetricsRegression) mm).rmsle()
      };
    }
    Log.warn("Failed to find metric for model: " + m);
    return new double[] {Double.NaN};
  }

  private static String[] defaultMetricNameForModel(Model m) {
    if (m._output.isBinomialClassifier()) {
      return new String[] {"auc", "logloss", "mean_per_class_error", "rmse", "mse"};
    } else if (m._output.isMultinomialClassifier()) {
      return new String[] {"mean per-class error", "logloss", "rmse", "mse"};
    } else if (m._output.isSupervised()) {
      return new String[] {"mean_residual_deviance", "rmse", "mse", "mae", "rmsle"};
    }
    return new String[] {"unknown"};
  }

  String rankTsv() {
    String lineSeparator = "\n";

    StringBuilder sb = new StringBuilder();
    sb.append("Error").append(lineSeparator);

    Model[] models = getModels();
    for (int i = models.length - 1; i >= 0; i--) {
      // TODO: allow the metric to be passed in.  Note that this assumes the validation (or training) frame is the same.
      Model m = models[i];
      sb.append(Arrays.toString(defaultMetricForModel(m)));
      sb.append(lineSeparator);
    }
    return sb.toString();
  }

  private static String[] colHeaders(String metric, String[] other_metric) {
    String[] headers = ArrayUtils.append(new String[]{"model_id",metric},other_metric);
    return headers;
  }

  private static final String[] colTypesMultinomial= {
          "string",
          "double",
          "double",
          "double",
          "double"
  };

  private static final String[] colFormatsMultinomial= {
          "%s",
          "%.6f",
          "%.6f",
          "%.6f",
          "%.6f"
  };

  private static final String[] colTypesBinomial= {
          "string",
          "double",
          "double",
          "double",
          "double",
          "double"
  };

  private static final String[] colFormatsBinomial= {
          "%s",
          "%.6f",
          "%.6f",
          "%.6f",
          "%.6f",
          "%.6f"
  };

  private static final String[] colTypesRegression= {
          "string",
          "double",
          "double",
          "double",
          "double",
          "double"
  };

  private static final String[] colFormatsRegression= {
          "%s",
          "%.6f",
          "%.6f",
          "%.6f",
          "%.6f",
          "%.6f"
  };

  private static final TwoDimTable makeTwoDimTable(String tableHeader, String sort_metric, String[] other_metrics, Model[] models) {
    assert sort_metric != null || models.length == 0 :
        "sort_metrics needs to be always not-null for non-empty array!";

    String[] rowHeaders = new String[models.length];
    for (int i = 0; i < models.length; i++) rowHeaders[i] = "" + i;

    if (models.length == 0) {
      // empty TwoDimTable
      return new TwoDimTable(tableHeader,
              "no models in this leaderboard",
              rowHeaders,
              Leaderboard.colHeaders("auc", other_metrics),
              Leaderboard.colTypesBinomial,
              Leaderboard.colFormatsBinomial,
              "-");
    }
    if(models[0]._output.isBinomialClassifier()) {
      //other_metrics =  new String[] {"logloss", "mean_per_class_error", "rmse", "mse"};
      return new TwoDimTable(tableHeader,
              "models sorted in order of " + sort_metric + ", best first",
              rowHeaders,
              Leaderboard.colHeaders("auc", other_metrics),
              Leaderboard.colTypesBinomial,
              Leaderboard.colFormatsBinomial,
              "#");
    } else if  (models[0]._output.isMultinomialClassifier()) {
      //other_metrics =  new String[] {"logloss", "rmse", "mse"};
      return new TwoDimTable(tableHeader,
              "models sorted in order of " + sort_metric + ", best first",
              rowHeaders,
              Leaderboard.colHeaders("mean_per_class_error", other_metrics),
              Leaderboard.colTypesMultinomial,
              Leaderboard.colFormatsMultinomial,
              "#");

    } else {
      //other_metrics = new String[] {"rmse", "mse", "mae","rmsle"};
      return new TwoDimTable(tableHeader,
              "models sorted in order of " + sort_metric + ", best first",
              rowHeaders,
              Leaderboard.colHeaders("mean_residual_deviance", other_metrics),
              Leaderboard.colTypesRegression,
              Leaderboard.colFormatsRegression,
              "#");
    }
  }


  private void addTwoDimTableRowMultinomial(TwoDimTable table, int row, String[] modelIDs, double[] mean_per_class_error, double[] logloss, double[] rmse, double[] mse) {
    int col = 0;
    table.set(row, col++, modelIDs[row]);
    //table.set(row, col++, timestampFormat.format(new Date(timestamps[row])));
    table.set(row, col++, mean_per_class_error[row]);
    table.set(row, col++, logloss[row]);
    table.set(row, col++, rmse[row]);
    table.set(row, col++, mse[row]);
  }

  private void addTwoDimTableRowBinomial(TwoDimTable table, int row, String[] modelIDs, double[] auc, double[] logloss, double[] mean_per_class_error, double[] rmse, double[] mse) {
    int col = 0;
    table.set(row, col++, modelIDs[row]);
    //table.set(row, col++, timestampFormat.format(new Date(timestamps[row])));
    table.set(row, col++, auc[row]);
    table.set(row, col++, logloss[row]);
    table.set(row, col++, mean_per_class_error[row]);
    table.set(row, col++, rmse[row]);
    table.set(row, col++, mse[row]);
  }

  private void addTwoDimTableRowRegression(TwoDimTable table, int row, String[] modelIDs, double[] mean_residual_deviance, double[] rmse, double[] mse, double[] mae, double[] rmsle) {
    int col = 0;
    table.set(row, col++, modelIDs[row]);
    //table.set(row, col++, timestampFormat.format(new Date(timestamps[row])));
    table.set(row, col++, mean_residual_deviance[row]);
    table.set(row, col++, rmse[row]);
    table.set(row, col++, mse[row]);
    table.set(row, col++, mae[row]);
    table.set(row, col++, rmsle[row]);
  }

  public TwoDimTable toTwoDimTable() {
    return toTwoDimTable("Leaderboard for AutoML: " + _project_name, false);
  }

  TwoDimTable toTwoDimTable(String tableHeader, boolean leftJustifyModelIds) {
    Model[] models = this.getModels();
    //long[] timestamps = getTimestamps(models);
    String[] modelIDsFormatted = new String[models.length];

    if (models.length == 0) { //No models due to exclude algos or ran out of time
      //Just use binomial metrics as a placeholder (no way to tell as user can pass in any metric to sort by)
      this._other_metrics = new String[] {"logloss", "mean_per_class_error", "rmse", "mse"};
    }
    else if(models[0]._output.isBinomialClassifier()) {
      this._other_metrics = new String[] {"logloss", "mean_per_class_error", "rmse", "mse"};
    } else if (models[0]._output.isMultinomialClassifier()) {
      this._other_metrics = new String[] {"logloss", "rmse", "mse"};
    } else {
      this._other_metrics = new String[] {"rmse", "mse", "mae","rmsle"};
    }

    TwoDimTable table = makeTwoDimTable(tableHeader, _sort_metric, _other_metrics, models);

    // %-s doesn't work in TwoDimTable.toString(), so fake it here:
    int maxModelIdLen = -1;
    for (Model m : models)
      maxModelIdLen = Math.max(maxModelIdLen, m._key.toString().length());
    for (int i = 0; i < models.length; i++)
      if (leftJustifyModelIds) {
        modelIDsFormatted[i] =
                (models[i]._key.toString() +
                        "                                                                                         ")
                        .substring(0, maxModelIdLen);
      } else {
        modelIDsFormatted[i] = models[i]._key.toString();
      }

    for (int i = 0; i < models.length; i++)
      //addTwoDimTableRow(table, i, modelIDsFormatted, timestamps, sort_metrics);
      if(models[i]._output.isMultinomialClassifier()){ //Multinomial case
        addTwoDimTableRowMultinomial(table, i, modelIDsFormatted, mean_per_class_error, logloss, rmse, mse);
      }else if(models[i]._output.isBinomialClassifier()) { //Binomial case
        addTwoDimTableRowBinomial(table, i, modelIDsFormatted, auc, logloss, mean_per_class_error, rmse, mse);
      }else { //Regression
        addTwoDimTableRowRegression(table, i, modelIDsFormatted, mean_residual_deviance, rmse, mse, mae, rmsle);
      }
    return table;
  }

  //private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS");

  private static String toString(String project_name, Model[] models, String fieldSeparator, String lineSeparator, boolean includeTitle, boolean includeHeader) {
    StringBuilder sb = new StringBuilder();
    if (includeTitle) {
      sb.append("Leaderboard for project_name \"")
              .append(project_name)
              .append("\": ");

      if (models.length == 0) {
        sb.append("<empty>");
        return sb.toString();
      }
      sb.append(lineSeparator);
    }

    boolean printedHeader = false;
    for (Model m : models) {
      // TODO: allow the metric to be passed in.  Note that this assumes the validation (or training) frame is the same.
      if (includeHeader && ! printedHeader) {
        sb.append("model_id");
        sb.append(fieldSeparator);

        sb.append(defaultMetricNameForModel(m));

        /*
        if (includeTimestamp) {
          sb.append(fieldSeparator);
          sb.append("timestamp");
        }
        */
        sb.append(lineSeparator);
        printedHeader = true;
      }

      sb.append(m._key.toString());
      sb.append(fieldSeparator);

      sb.append(defaultMetricForModel(m));

      /*
      if (includeTimestamp) {
        sb.append(fieldSeparator);
        sb.append(timestampFormat.format(m._output._end_time));
      }
      */

      sb.append(lineSeparator);
    }
    return sb.toString();
  }

  private Frame leaderboardFrame() {
    return _leaderboardFrameKey == null ? null : _leaderboardFrameKey.get();
  }

  private String toString(String fieldSeparator, String lineSeparator) {
    return toString(_project_name, getModels(), fieldSeparator, lineSeparator, true, true);
  }

  @Override
  public String toString() {
    return toString(" ; ", " | ");
  }
  
}
