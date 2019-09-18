from h2o.estimators.xgboost import *
import sys
sys.path.insert(1,"../../../")
from tests import pyunit_utils
import random

def test_try_to_get_npe():
    runSeed = 1
    testTol = 1e-10
    ntrees = 1
    maxdepth = 5
    h2oParamsD = {"ntrees":ntrees, "max_depth":maxdepth, "seed":runSeed, "learn_rate":0.7, "col_sample_rate_per_tree" : 0.9,
                 "min_rows" : 5, "score_tree_interval": ntrees+1, "dmatrix_type":"sparse", "tree_method": "exact", "backend":"cpu"}

    nrows = 1_000_000
    ncols = 1_000
    factorL = 11
    numCols = 800
    enumCols = ncols-numCols
    responseL = 2
    y='response'

    while True:
        dataSeed = random.randint(0,nrows)
        trainFile = pyunit_utils.genTrainFrame(nrows, numCols, enumCols=enumCols, enumFactors=factorL, miscfrac=0.5,
                                               responseLevel=responseL, randseed=dataSeed)
        myX = trainFile.names
        myX.remove(y)
    
        h2oModelD = H2OXGBoostEstimator(**h2oParamsD)
        # gather, print and save performance numbers for h2o model
        h2oModelD.train(x=myX, y=y, training_frame=trainFile)

if __name__ == "__main__":
    pyunit_utils.standalone_test(test_try_to_get_npe)
else:
    test_try_to_get_npe()
