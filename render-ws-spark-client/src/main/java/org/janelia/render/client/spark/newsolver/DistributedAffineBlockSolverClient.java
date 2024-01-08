package org.janelia.render.client.spark.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.models.AffineModel2D;
import mpicbg.models.NoninvertibleModelException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.storage.StorageLevel;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.DistributedAffineBlockSolver;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.DistributedSolveParameters;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

/**
 * Spark client for running a DistributedAffineBlockSolve.
 */
public class DistributedAffineBlockSolverClient
        implements Serializable, AlignmentPipelineStep {

    /**
     * Run the client with command line parameters.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args)
                    throws Exception {
                final AffineBlockSolverSetup parameters = new AffineBlockSolverSetup();
                parameters.parse(args);
                final DistributedAffineBlockSolverClient client = new DistributedAffineBlockSolverClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /**
     * Empty constructor required for alignment pipeline steps.
     */
    public DistributedAffineBlockSolverClient() {
    }

    /**
     * Create a spark context and run the client with the specified setup.
     */
    public void createContextAndRun(final AffineBlockSolverSetup affineBlockSolverSetup)
            throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            alignSetupList(sparkContext, Collections.singletonList(affineBlockSolverSetup));
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("affineBlockSolverSetup",
                                                                  pipelineParameters.getAffineBlockSolverSetup());
    }

    /**
     * Run the client as part of an alignment pipeline.
     */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject();
        final List<AffineBlockSolverSetup> setupList = new ArrayList<>();
        final AffineBlockSolverSetup setup = pipelineParameters.getAffineBlockSolverSetup();
        final List<StackWithZValues> stackList = multiProject.buildListOfStackWithAllZ();
        final int nRuns = setup.alternatingRuns.nRuns;

        // TODO: push StackWithZValues idea into core solver code
        for (final StackWithZValues stackWithZValues : stackList) {
            setup.setValuesFromPipeline(multiProject.getBaseDataUrl(),
                                        stackWithZValues.getStackId());
            setupList.add(setup.clone());
        }

        final DistributedAffineBlockSolverClient affineBlockSolverClient = new DistributedAffineBlockSolverClient();

        if (nRuns == 1) {

            affineBlockSolverClient.alignSetupList(sparkContext, setupList);

        } else {

            // TODO: handle alternatingRuns for multiple stacks
            if (stackList.size() > 1) {
                throw new IllegalArgumentException("alternatingRuns is not supported for multiple stacks");
            }

            final AffineBlockSolverSetup updatedSetup = setupList.get(0);
            String sourceStack = updatedSetup.stack;
            final String originalTargetStack = updatedSetup.targetStack.stack;

            for (int runNumber = 1; runNumber <= nRuns; runNumber++) {

                final String targetStack = getStackName(originalTargetStack, runNumber, nRuns);

                final AffineBlockSolverSetup runSetup = updatedSetup.clone();
                runSetup.stack = sourceStack;
                runSetup.targetStack.stack = targetStack;
                updateParameters(runSetup, runNumber);

                LOG.info("runPipelineStep: run {} of {}, stack={}, targetStack={}, shiftBlocks={}",
                         runNumber, nRuns, sourceStack, targetStack, runSetup.blockPartition.shiftBlocks);

                affineBlockSolverClient.alignSetupList(sparkContext, Collections.singletonList(runSetup));

                if ((!runSetup.alternatingRuns.keepIntermediateStacks) && (runNumber > 1))
                    cleanUpIntermediateStack(runSetup);

                sourceStack = runSetup.targetStack.stack;
            }
        }

    }

    private void alignSetupList(final JavaSparkContext sparkContext,
                                final List<AffineBlockSolverSetup> setupList)
            throws IOException {

        LOG.info("alignSetupList: entry, setupList={}", setupList);

        final int parallelism = deriveParallelismValues(sparkContext, setupList);

        final List<DistributedAffineBlockSolver> solverList = new ArrayList<>();
        final List<Tuple2<Integer, BlockData<AffineModel2D, ?>>> inputBlocksWithSetupIndexes = new ArrayList<>();

        buildSolversAndInputBlocks(setupList, solverList, inputBlocksWithSetupIndexes);

        final JavaPairRDD<Integer, BlockData<AffineModel2D, ?>> rddInputBlocks =
                sparkContext.parallelizePairs(inputBlocksWithSetupIndexes, parallelism);

        final JavaPairRDD<Integer, BlockData<AffineModel2D, ?>> rddOutputBlocks =
                rddInputBlocks.flatMapToPair(tuple2 -> solveInputBlock(setupList, tuple2._1, tuple2._2));

        if (setupList.size() > 1) {
            globallySolveMultipleSetups(setupList, rddOutputBlocks, solverList);
        } else {
            final List<BlockData<AffineModel2D, ?>> outputBlocks = rddOutputBlocks.values().collect();
            globallySolveOneSetup(setupList, 0, solverList, outputBlocks);
        }

        LOG.info("alignSetupList: exit");
    }

    // TODO: these methods are very close to those in ADDSolverClient, need to refactor to reuse as much as possible

    private static String getStackName(final String name, final int runNumber, final int nTotalRuns) {
        if (runNumber == nTotalRuns) {
            return name;
        } else {
            return name + "_run" + runNumber;
        }
    }

    private static void cleanUpIntermediateStack(final AffineBlockSolverSetup parameters) {
        final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
        try {
            dataClient.deleteStack(parameters.stack, null);
            LOG.info("cleanUpIntermediateStack: deleted stack {}", parameters.stack);
        } catch (final IOException e) {
            LOG.error("cleanUpIntermediateStack: error deleting stack {}", parameters.stack, e);
        }
    }

    private static void updateParameters(final AffineBlockSolverSetup parameters, final int runNumber) {
        // alternate block layout
        parameters.blockPartition.shiftBlocks = (runNumber % 2 == 1);

        // don't stitch or pre-align after first run
        if (runNumber > 1) {
            parameters.stitchFirst = false;
            parameters.preAlign = FIBSEMAlignmentParameters.PreAlign.NONE;
        }
    }

    private static int deriveParallelismValues(final JavaSparkContext sparkContext,
                                               final List<AffineBlockSolverSetup> setupList) {

        // From https://spark.apache.org/docs/3.4.1/configuration.html#execution-behavior ...
        //   For these cluster managers, spark.default.parallelism is:
        //   - Local mode: number of cores on the local machine
        //   - Mesos fine grained mode: 8
        //   - Others: total number of cores on all executor nodes or 2, whichever is larger.
        int parallelism = sparkContext.defaultParallelism();
        final DistributedSolveParameters firstSetupSolveParameters = setupList.get(0).distributedSolve;

        LOG.info("deriveParallelismValues: entry, threadsGlobal={}, threadsWorker={}, parallelism={}",
                 firstSetupSolveParameters.threadsGlobal, firstSetupSolveParameters.threadsWorker, parallelism);

        if (firstSetupSolveParameters.deriveThreadsUsingSparkConfig) {

            final SparkConf sparkConf = sparkContext.getConf();
            final int driverCores = sparkConf.getInt("spark.driver.cores", 1);
            final int executorCores = sparkConf.getInt("spark.executor.cores", 1);

            setupList.forEach(setup -> {
                setup.distributedSolve.threadsGlobal = driverCores;
                setup.distributedSolve.threadsWorker = executorCores;
            });

            // set parallelism to number of worker executors
            // see https://stackoverflow.com/questions/51342460/getexecutormemorystatus-size-not-outputting-correct-num-of-executors
            final int numberOfExecutorsIncludingDriver = sparkContext.sc().getExecutorMemoryStatus().size();
            final int numberOfWorkerExecutors = numberOfExecutorsIncludingDriver - 1;
            parallelism = Math.max(numberOfWorkerExecutors, 2);

            LOG.info("deriveParallelismValues: updated values, threadsGlobal={}, threadsWorker={}, parallelism={}",
                     driverCores, executorCores, parallelism);
        }

        return parallelism;
    }

    private static void buildSolversAndInputBlocks(final List<AffineBlockSolverSetup> setupList,
                                                   final List<DistributedAffineBlockSolver> solverList,
                                                   final List<Tuple2<Integer, BlockData<AffineModel2D, ?>>> allInputBlocksWithSetupIndexes)
            throws IOException {

        for (int setupIndex = 0; setupIndex < setupList.size(); setupIndex++) {
            final AffineBlockSolverSetup setup = setupList.get(setupIndex);

            final RenderSetup renderSetup = RenderSetup.setupSolve(setup);
            final DistributedAffineBlockSolver solver = new DistributedAffineBlockSolver(setup, renderSetup);
            solverList.add(solver);

            final BlockCollection<?, AffineModel2D, ?> blockCollection =
                    solver.setupSolve(setup.blockOptimizer.getModel(),
                                      setup.stitching.getModel());

            final List<BlockData<AffineModel2D, ?>> allInputBlocksForSetup =
                    new ArrayList<>(blockCollection.allBlocks());

            LOG.info("buildSolversAndInputBlocks: setup index {}, created {} input blocks: {}",
                     setupIndex, allInputBlocksForSetup.size(), allInputBlocksForSetup);

            for (final BlockData<AffineModel2D, ?> block : allInputBlocksForSetup) {
                allInputBlocksWithSetupIndexes.add(new Tuple2<>(setupIndex, block));
            }
        }
    }

    private static Iterator<Tuple2<Integer, BlockData<AffineModel2D, ?>>> solveInputBlock(final List<AffineBlockSolverSetup> setupList,
                                                                                          final int setupIndex,
                                                                                          final BlockData<AffineModel2D, ?> inputBlock)
            throws NoninvertibleModelException, IOException, ExecutionException, InterruptedException {

        LogUtilities.setupExecutorLog4j(""); // block info already in most log calls so leave context empty

        final AffineBlockSolverSetup setup = setupList.get(setupIndex);
        final List<BlockData<AffineModel2D, ?>> outputBlockList =
                DistributedAffineBlockSolver.createAndRunWorker(inputBlock, setup);

        final List<Tuple2<Integer, BlockData<AffineModel2D, ?>>> outputBlocksWithSetupIndexes =
                new ArrayList<>(outputBlockList.size());
        for (final BlockData<AffineModel2D, ?> outputBlock : outputBlockList) {
            outputBlocksWithSetupIndexes.add(new Tuple2<>(setupIndex, outputBlock));
        }

        return outputBlocksWithSetupIndexes.iterator();
    }

    private static void globallySolveOneSetup(final List<AffineBlockSolverSetup> setupList,
                                              final Integer setupIndex,
                                              final List<DistributedAffineBlockSolver> solverList,
                                              final List<BlockData<AffineModel2D, ?>> outputBlocksForSetup)
            throws IOException {

        final AffineBlockSolverSetup setup = setupList.get(setupIndex);
        final DistributedAffineBlockSolver solver = solverList.get(setupIndex);

        LOG.info("globallySolveOneSetup: setup index {}, solving {} blocks with {} threads",
                 setupIndex, outputBlocksForSetup.size(), setup.distributedSolve.threadsGlobal);

        DistributedAffineBlockSolver.solveCombineAndSaveBlocks(setup,
                                                               outputBlocksForSetup,
                                                               solver);
    }

    private static void globallySolveMultipleSetups(final List<AffineBlockSolverSetup> setupList,
                                                    final JavaPairRDD<Integer, BlockData<AffineModel2D, ?>> rddOutputBlocks,
                                                    final List<DistributedAffineBlockSolver> solverList)
            throws IOException {

        Integer previousSetupIndex = null;
        final List<BlockData<AffineModel2D, ?>> outputBlocksForSetup = new ArrayList<>();

        // To avoid collecting all blocks into memory on the driver at once:

        // 1. Persist the solved output blocks so that they don't need to be recalculated after sorting.
        //
        //    The MEMORY_AND_DISK storage level indicates that the RDD is stored as deserialized Java objects
        //    in the JVM. If the RDD does not fit in memory, partitions that don't fit are stored on disk
        //    and then read from disk when they're needed.
        rddOutputBlocks.persist(StorageLevel.MEMORY_AND_DISK());

        // 2. Sort (by setupIndex) so that blocks for the same setup are adjacent.
        final JavaPairRDD<Integer, BlockData<AffineModel2D, ?>> outputBlocksSortedBySetup = rddOutputBlocks.sortByKey();

        // 3. Use toLocalIterator to pull the solved blocks to the driver one at a time.
        final Iterator<Tuple2<Integer, BlockData<AffineModel2D, ?>>> localIterator = outputBlocksSortedBySetup.toLocalIterator();

        while (localIterator.hasNext()) {

            final Tuple2<Integer, BlockData<AffineModel2D, ?>> tuple2 = localIterator.next();
            final int setupIndex = tuple2._1;
            final BlockData<AffineModel2D, ?> outputBlock = tuple2._2;

            if ((previousSetupIndex != null) && (previousSetupIndex != setupIndex)) {
                globallySolveOneSetup(setupList, previousSetupIndex, solverList, outputBlocksForSetup);
                outputBlocksForSetup.clear();
            }

            outputBlocksForSetup.add(outputBlock);
            previousSetupIndex = setupIndex;
        }

        if (outputBlocksForSetup.isEmpty()) {
            throw new IOException("no blocks were computed for setupIndex " + previousSetupIndex +
                                  ", something is wrong");
        } else {
            globallySolveOneSetup(setupList, previousSetupIndex, solverList, outputBlocksForSetup);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DistributedAffineBlockSolverClient.class);
}
