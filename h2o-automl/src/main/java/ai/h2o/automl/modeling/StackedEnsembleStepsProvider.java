package ai.h2o.automl.modeling;

import ai.h2o.automl.*;
import ai.h2o.automl.WorkAllocations.Work;
import hex.Model;
import hex.ensemble.StackedEnsembleModel;
import hex.ensemble.StackedEnsembleModel.StackedEnsembleParameters;
import water.Job;
import water.Key;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ai.h2o.automl.ModelingStep.ModelStep.DEFAULT_MODEL_TRAINING_WEIGHT;

public class StackedEnsembleStepsProvider implements ModelingStepsProvider<StackedEnsembleStepsProvider.StackedEnsembleSteps> {

    public static class StackedEnsembleSteps extends ModelingSteps {

        static abstract class StackedEnsembleModelStep extends ModelingStep.ModelStep<StackedEnsembleModel> {

            StackedEnsembleModelStep(String id, int weight, AutoML autoML) {
                super(Algo.StackedEnsemble, id, weight, autoML);
                _ignoreConstraints = true;
            }

            @Override
            protected boolean canRun() {
                Model[] allModels = getTrainedModels();
                Work seWork = getAllocatedWork();
                if (seWork == null) {
                    aml().job().update(0, "StackedEnsemble builds skipped");
                    aml().eventLog().info(EventLogEntry.Stage.ModelTraining, "StackedEnsemble builds skipped due to the exclude_algos option.");
                    return false;
                } else if (allModels.length == 0) {
                    aml().job().update(seWork.consume(), "No models built; StackedEnsemble builds skipped");
                    aml().eventLog().info(EventLogEntry.Stage.ModelTraining, "No models were built, due to timeouts or the exclude_algos option. StackedEnsemble builds skipped.");
                    return false;
                } else if (allModels.length == 1) {
                    aml().job().update(seWork.consume(), "One model built; StackedEnsemble builds skipped");
                    aml().eventLog().info(EventLogEntry.Stage.ModelTraining, "StackedEnsemble builds skipped since there is only one model built");
                    return false;
                } else if (!isCVEnabled() && aml().getBlendingFrame() == null) {
                    aml().job().update(seWork.consume(), "Cross-validation disabled by the user and no blending frame provided; StackedEnsemble build skipped");
                    aml().eventLog().info(EventLogEntry.Stage.ModelTraining, "Cross-validation disabled by the user and no blending frame provided; StackedEnsemble build skipped");
                    return false;
                }
                return true;
            }

            String getModelType(Model m) {
                String keyStr = m._key.toString();
                return keyStr.substring(0, keyStr.indexOf('_'));
            }

            Job<StackedEnsembleModel> stack(String modelName, Key<Model>[] modelKeyArrays, boolean isLast) {
                AutoMLBuildSpec buildSpec = aml().getBuildSpec();
                // Set up Stacked Ensemble
                StackedEnsembleModel.StackedEnsembleParameters stackedEnsembleParameters = new StackedEnsembleParameters();
                stackedEnsembleParameters._base_models = modelKeyArrays;
                stackedEnsembleParameters._valid = (aml().getValidationFrame() == null ? null : aml().getValidationFrame()._key);
                stackedEnsembleParameters._blending = (aml().getBlendingFrame() == null ? null : aml().getBlendingFrame()._key);
                stackedEnsembleParameters._keep_levelone_frame = true; //TODO Why is this true? Can be optionally turned off
                stackedEnsembleParameters._keep_base_model_predictions = !isLast; //avoids recomputing some base predictions for each SE
                // Add cross-validation args
                stackedEnsembleParameters._metalearner_fold_column = buildSpec.input_spec.fold_column;
                stackedEnsembleParameters._metalearner_nfolds = buildSpec.build_control.nfolds;

                stackedEnsembleParameters.initMetalearnerParams();
                stackedEnsembleParameters._metalearner_parameters._keep_cross_validation_models = buildSpec.build_control.keep_cross_validation_models;
                stackedEnsembleParameters._metalearner_parameters._keep_cross_validation_predictions = buildSpec.build_control.keep_cross_validation_predictions;

                Key<StackedEnsembleModel> modelKey = makeKey(modelName, false);
                return trainModel(modelKey, stackedEnsembleParameters);
            }

        }


        private ModelingStep[] defaults = new StackedEnsembleModelStep[] {
                new StackedEnsembleModelStep("best", DEFAULT_MODEL_TRAINING_WEIGHT, aml()) {
                    { _description = _description+" (built using top model from each algorithm type)"; }
                    @Override
                    protected Job<StackedEnsembleModel> startJob() {
                        // Set aside List<Model> for best models per model type. Meaning best GLM, GBM, DRF, XRT, and DL (5 models).
                        // This will give another ensemble that is smaller than the original which takes all models into consideration.
                        List<Model> bestModelsOfEachType = new ArrayList<>();
                        Set<String> typesOfGatheredModels = new HashSet<>();

                        for (Model aModel : getTrainedModels()) {
                            String type = getModelType(aModel);
                            if (aModel instanceof StackedEnsembleModel || typesOfGatheredModels.contains(type)) continue;
                            typesOfGatheredModels.add(type);
                            bestModelsOfEachType.add(aModel);
                        }

                        Key<Model>[] bestModelKeys = new Key[bestModelsOfEachType.size()];
                        for (int i = 0; i < bestModelsOfEachType.size(); i++)
                            bestModelKeys[i] = bestModelsOfEachType.get(i)._key;

                        return stack(_algo+"_BestOfFamily", bestModelKeys, false);
                    }
                },
                new StackedEnsembleModelStep("all", DEFAULT_MODEL_TRAINING_WEIGHT, aml()) {
                    { _description = _description+" (built using all AutoML models)"; }
                    @Override
                    protected Job<StackedEnsembleModel> startJob() {
                        int nonEnsembleCount = 0;
                        for (Model aModel : getTrainedModels())
                            if (!(aModel instanceof StackedEnsembleModel))
                                nonEnsembleCount++;

                        Key<Model>[] notEnsemblesKeys = new Key[nonEnsembleCount];
                        int notEnsembleIndex = 0;
                        for (Model aModel : getTrainedModels())
                            if (!(aModel instanceof StackedEnsembleModel))
                                notEnsemblesKeys[notEnsembleIndex++] = aModel._key;

                        return stack(_algo+"_AllModels", notEnsemblesKeys, true);
                    }
                },
        };

        private ModelingStep[] grids = new ModelingStep[0];

        public StackedEnsembleSteps(AutoML autoML) {
            super(autoML);
        }

        @Override
        protected ModelingStep[] getDefaultModels() {
            return defaults;
        }

        @Override
        protected ModelingStep[] getGrids() {
            return grids;
        }
    }

    @Override
    public String getName() {
        return Algo.StackedEnsemble.name();
    }

    @Override
    public StackedEnsembleSteps newInstance(AutoML aml) {
        return new StackedEnsembleSteps(aml);
    }
}

