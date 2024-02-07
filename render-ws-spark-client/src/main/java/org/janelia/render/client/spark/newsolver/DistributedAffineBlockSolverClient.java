package org.janelia.render.client.spark.newsolver;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpicbg.models.AffineModel2D;
import mpicbg.models.NoninvertibleModelException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.storage.StorageLevel;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.newsolver.BlockCollection;
import org.janelia.render.client.newsolver.BlockData;
import org.janelia.render.client.newsolver.DistributedAffineBlockSolver;
import org.janelia.render.client.newsolver.AlternatingSolveUtils;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.newsolver.setup.DistributedSolveParameters;
import org.janelia.render.client.newsolver.setup.RenderSetup;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nonnull;
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

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());
        final List<AffineBlockSolverSetup> setupList = new ArrayList<>();
        final AffineBlockSolverSetup setup = pipelineParameters.getAffineBlockSolverSetup();
        final List<StackWithZValues> stackList = multiProject.buildListOfStackWithAllZ();
        final int nRuns = setup.alternatingRuns.nRuns;
        final boolean cleanUpIntermediateStacks = ! setup.alternatingRuns.keepIntermediateStacks;

        final String matchSuffix = pipelineParameters.getMatchCopyToCollectionSuffix();
        for (final StackWithZValues stackWithZValues : stackList) {
            setupList.add(setup.buildPipelineClone(multiProject.getBaseDataUrl(),
                                                   stackWithZValues,
                                                   multiProject.deriveMatchCollectionNamesFromProject,
                                                   matchSuffix));
        }

        final DistributedAffineBlockSolverClient affineBlockSolverClient = new DistributedAffineBlockSolverClient();

        if (nRuns == 1) {

            affineBlockSolverClient.alignSetupList(sparkContext, setupList);

        } else {

            // Different stacks can be aligned in parallel, but each run must be done sequentially.
            // So, for each run, create a list of setups that will be aligned in parallel.
            final List<List<AffineBlockSolverSetup>> setupListsForRuns = buildSetupListsForRuns(nRuns, setupList);

            // loop through each run and align the stacks in parallel ...
            for (int runIndex = 0; runIndex < nRuns; runIndex++) {

                final List<AffineBlockSolverSetup> setupListForRun = setupListsForRuns.get(runIndex);

                // align all stacks for this run
                affineBlockSolverClient.alignSetupList(sparkContext, setupListForRun);

                // clean-up intermediate stacks for prior runs if requested
                if (cleanUpIntermediateStacks && (runIndex > 0)) {
                    setupListForRun.forEach(s -> AlternatingSolveUtils.cleanUpIntermediateStack(s.renderWeb,
                                                                                                s.stack));
                }
            }

        }

    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.ALIGN_TILES;
    }

    @Nonnull
    private List<List<AffineBlockSolverSetup>> buildSetupListsForRuns(final int nRuns,
                                                                      final List<AffineBlockSolverSetup> setupList) {

        final List<List<AffineBlockSolverSetup>> setupListsForRuns = new ArrayList<>(nRuns);
        IntStream.range(0, nRuns).forEach(i -> setupListsForRuns.add(new ArrayList<>(setupList.size())));

        for (final AffineBlockSolverSetup updatedSetup : setupList) {

            String runSourceStack = updatedSetup.stack;
            final String originalTargetStack = updatedSetup.targetStack.stack;

            for (int runNumber = 1; runNumber <= nRuns; runNumber++) {

                final List<AffineBlockSolverSetup> setupListForRun = setupListsForRuns.get(runNumber - 1);

                final AffineBlockSolverSetup runSetup = updatedSetup.clone();
                runSetup.stack = runSourceStack;
                runSetup.targetStack.stack = AlternatingSolveUtils.getStackNameForRun(originalTargetStack,
                                                                                      runNumber,
                                                                                      nRuns);
                AlternatingSolveUtils.updateParametersForNextRun(runSetup, runNumber);

                setupListForRun.add(runSetup);

                LOG.info("buildSetupListsForRuns: added setup for stack {}, run {}, targetStack={}, shiftBlocks={}",
                         runSourceStack, runNumber, runSetup.targetStack.stack, runSetup.blockPartition.shiftBlocks);

                runSourceStack = runSetup.targetStack.stack;
            }
        }

        return setupListsForRuns;
    }

    private void alignSetupList(final JavaSparkContext sparkContext,
                                final List<AffineBlockSolverSetup> setupList)
            throws IOException {

        LOG.info("alignSetupList: entry, setupList={}", setupList);

        final List<DistributedSolveParameters> solveParameters = setupList.stream()
                .map(setup -> setup.distributedSolve)
                .collect(Collectors.toList());
        final int parallelism = SparkDistributedSolveUtils.deriveParallelismValues(sparkContext, solveParameters);

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
                                                    final List<DistributedAffineBlockSolver> solverList) {

        LOG.info("globallySolveMultipleSetups: entry, solving {} setups", setupList.size());

        // 1. Persist the solved output blocks so that they don't need to be recalculated during combination.
        //
        //    The MEMORY_AND_DISK storage level indicates that the RDD is stored as deserialized Java objects
        //    in the JVM. If the RDD does not fit in memory, partitions that don't fit are stored on disk
        //    and then read from disk when they're needed.
        rddOutputBlocks.persist(StorageLevel.MEMORY_AND_DISK());

        // 2. Combine the solved output blocks for each setup (stack) into a list so that the list of blocks
        //    can be solved globally on a Spark executor.  Doing the global solve on executor/workers allows
        //    global solves for different stacks to be run concurrently.
        final JavaPairRDD<Integer, List<BlockData<AffineModel2D, ?>>> outputBlocksForSetupRdd =
                rddOutputBlocks.combineByKey(
                        // createCombiner
                        block -> {
                            final List<BlockData<AffineModel2D, ?>> list = new ArrayList<>();
                            list.add(block);
                            return list;
                        },
                        // mergeValue
                        (list, block) -> {
                            list.add(block);
                            return list;
                        },
                        // mergeCombiners
                        (list1, list2) -> {
                            list1.addAll(list2);
                            return list1;
                        }
                );

        // 3. Run the global solve for each setup (stack) in parallel.
        final JavaRDD<StackId> globallySolvedTargetStackIdsRdd =
                outputBlocksForSetupRdd.map(
                        setupIndexWithOutputBlocks -> runGlobalSolveOnExecutors(setupList,
                                                                                solverList,
                                                                                setupIndexWithOutputBlocks));

        // 4. Collect the target stack ids for each setup (stack) that was globally solved.
        final List<String> globallySolvedTargetStackDevStrings =
                globallySolvedTargetStackIdsRdd.collect().stream()
                        .sorted()
                        .map(StackId::toDevString)
                        .collect(Collectors.toList());

        LOG.info("globallySolveMultipleSetups: exit, globally solved {} setups stacks: {}",
                 globallySolvedTargetStackDevStrings.size(), globallySolvedTargetStackDevStrings);
    }

    @Nonnull
    private static StackId runGlobalSolveOnExecutors(final List<AffineBlockSolverSetup> setupList,
                                                     final List<DistributedAffineBlockSolver> solverList,
                                                     final Tuple2<Integer, List<BlockData<AffineModel2D, ?>>> setupIndexWithOutputBlocks)
            throws IOException {

        final int setupIndex = setupIndexWithOutputBlocks._1;
        final List<BlockData<AffineModel2D, ?>> outputBlocks = setupIndexWithOutputBlocks._2;

        LogUtilities.setupExecutorLog4j("setupIndex" + setupIndex); // add setup index to log context

        globallySolveOneSetup(setupList, setupIndex, solverList, outputBlocks);

        final AffineBlockSolverSetup setup = setupList.get(setupIndex);

        return new StackId(setup.targetStack.owner, setup.targetStack.project, setup.targetStack.stack);
    }

    private static final Logger LOG = LoggerFactory.getLogger(DistributedAffineBlockSolverClient.class);
}
