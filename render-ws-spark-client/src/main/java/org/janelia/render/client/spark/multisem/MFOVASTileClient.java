package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.match.ConnectedTileClusterSummaryForStack;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.ClusterCountClient;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.match.RemoveMatchPairClient;
import org.janelia.render.client.multisem.MFOVAsTileMontageMatchPatchClient;
import org.janelia.render.client.multisem.MFOVAsTileStackClient;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MFOVAsTileParameters;
import org.janelia.render.client.parameter.MFOVAsTileStackLists;
import org.janelia.render.client.parameter.MFOVMontageMatchPatchParameters;
import org.janelia.render.client.parameter.MatchPairRemovalParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileClusterParameters;
import org.janelia.render.client.parameter.TileRenderParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.match.MultiStagePointMatchClient;
import org.janelia.render.client.spark.newsolver.DistributedAffineBlockSolverClient;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nonnull;

/**
 * Spark client for ...
 */
public class MFOVASTileClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public MFOVAsTileParameters mfovAsTile = new MFOVAsTileParameters();

        public Parameters() {
        }

        public Parameters(final MultiProjectParameters multiProject,
                          final MFOVAsTileParameters mfovAsTile) {
            this.multiProject = multiProject;
            this.mfovAsTile = mfovAsTile;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final MFOVASTileClient client = new MFOVASTileClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public MFOVASTileClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
            run(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("mfovAsTile",
                                                                  pipelineParameters.getMfovAsTile());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {
        final Parameters clientParameters = new Parameters();
        clientParameters.multiProject = pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());
        clientParameters.mfovAsTile = pipelineParameters.getMfovAsTile();
        run(sparkContext, clientParameters);
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.RENDER_TILES;
    }

    private void run(final JavaSparkContext sparkContext,
                     final Parameters clientParameters)
            throws IllegalArgumentException, IOException {

        LOG.info("run: entry, clientParameters={}", clientParameters);

        final String baseDataUrl = clientParameters.multiProject.getBaseDataUrl();

        final MFOVAsTileStackLists mfovAsTileStackLists = new MFOVAsTileStackLists(baseDataUrl,
                                                                                   clientParameters.multiProject,
                                                                                   clientParameters.mfovAsTile);
        if (clientParameters.mfovAsTile.doPrealign()) {
            alignAndIntensityCorrectMfovAsTileStacks(sparkContext, mfovAsTileStackLists);
        }

        buildDynamicMfovAsTileStacks(sparkContext, mfovAsTileStackLists);

        buildRenderedMfovAsTileStacks(sparkContext, mfovAsTileStackLists);

        generateMfovAsTileMatches(sparkContext, mfovAsTileStackLists);

        removeCrossResinMfovAsTileMatches(sparkContext, mfovAsTileStackLists);

        patchMissingMfovAsTileMatches(sparkContext, mfovAsTileStackLists);

        alignRenderedMfovAsTileStacks(sparkContext, mfovAsTileStackLists);

        buildRoughSfovStacks(sparkContext, mfovAsTileStackLists);

        LOG.info("run: exit");
    }

    private static void alignAndIntensityCorrectMfovAsTileStacks(final JavaSparkContext sparkContext,
                                                                 final MFOVAsTileStackLists mfovAsTileStackLists)
            throws IOException {
        LOG.info("alignAndIntensityCorrectMfovAsTileStacks: entry");

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final String prealignedSfovStackSuffix  = mfovAsTileStackLists.getMfovAsTile().getPrealignedSfovStackSuffix();
        final List<StackWithZValues> rawSfovStacksWithAllZ = mfovAsTileStackLists.getRawSfovStacksWithAllZ();

        // Collect all MFOV processing tasks
        final List<MfovPrealignTask> mfovTasks = new ArrayList<>();
        final List<StackId> prealignedStackIds = new ArrayList<>();

        for (final StackWithZValues rawSfovStackWithZ : rawSfovStacksWithAllZ) {
            final StackId rawSfovStackId = rawSfovStackWithZ.getStackId();
            final StackId prealignedStackId = rawSfovStackId.withStackSuffix(prealignedSfovStackSuffix);

            if (mfovAsTileStackLists.isExistingStack(prealignedStackId)) {
                LOG.info("alignAndIntensityCorrectMfovAsTileStacks: skipping build of {} because it already exists",
                         prealignedStackId.toDevString());
                continue;
            }

            // Create prealigned stack
            final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                     rawSfovStackId.getOwner(),
                                                                     rawSfovStackId.getProject());
            final StackMetaData rawStackMetaData = dataClient.getStackMetaData(rawSfovStackId.getStack());
            dataClient.setupDerivedStack(rawStackMetaData, prealignedStackId.getStack());
            prealignedStackIds.add(prealignedStackId);

            // Collect MFOV tasks for each layer
            for (final Double z : rawSfovStackWithZ.getzValues()) {
                final List<String> mfovNames = MultiProjectParameters.getSortedMFOVNamesForOneLayer(dataClient,
                                                                                                    rawSfovStackId.getStack(),
                                                                                                    z);
                for (final String mfovName : mfovNames) {
                    mfovTasks.add(new MfovPrealignTask(baseDataUrl,
                                                        rawSfovStackId,
                                                        prealignedStackId,
                                                        new LayerMFOV(z, mfovName))
                    );
                }
            }
        }

        if (! mfovTasks.isEmpty()) {

            // Note: it takes about 1 minute to process 1 MFOV with 91 SFOV tiles
            final int parallelism = Math.min(MAX_PARTITIONS_FOR_ONE_WEB_SERVER, mfovTasks.size());

            LOG.info("alignAndIntensityCorrectMfovAsTileStacks: distributing {} MFOV tasks across {} stacks with parallelism {} (defaultParallelism={})",
                     mfovTasks.size(), prealignedStackIds.size(), parallelism, sparkContext.defaultParallelism());

            final JavaRDD<MfovPrealignTask> rddMfovTasks = sparkContext.parallelize(mfovTasks);
            rddMfovTasks.foreach(MfovPrealignTask::run);

            // Complete all prealigned stacks
            for (final StackId prealignedStackId : prealignedStackIds) {
                final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                         prealignedStackId.getOwner(),
                                                                         prealignedStackId.getProject());
                LOG.info("alignAndIntensityCorrectMfovAsTileStacks: completing stack {}",
                         prealignedStackId.toDevString());
                dataClient.setStackState(prealignedStackId.getStack(), StackMetaData.StackState.COMPLETE);
            }
        }

        LOG.info("alignAndIntensityCorrectMfovAsTileStacks: exit");
    }

    private static void buildDynamicMfovAsTileStacks(final JavaSparkContext sparkContext,
                                                     final MFOVAsTileStackLists mfovAsTileStackLists) {

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final MFOVAsTileParameters mfovAsTile = mfovAsTileStackLists.getMfovAsTile();
        final double mfovRenderScale = mfovAsTile.getMfovRenderScale();
        final String dynamicMfovStackSuffix = mfovAsTile.getDynamicMfovStackSuffix();

        final List<StackWithZValues> prealignedSfovStacksWithAllZ = mfovAsTileStackLists.getPrealignedSfovStacksWithAllZ();

        LOG.info("buildDynamicMfovAsTileStacks: entry, distributing build of {} stack(s)", prealignedSfovStacksWithAllZ.size());

        final JavaRDD<StackWithZValues> rddPrealignedSfovStacks = sparkContext.parallelize(prealignedSfovStacksWithAllZ);

        final Function<StackWithZValues, StackId> buildMfovStackFunction = stackWithAllZ -> {

            StackId builtStackId = null;

            LogUtilities.setupExecutorLog4j(stackWithAllZ.getStackId().toDevString());

            final StackId prealignedStackId = stackWithAllZ.getStackId();
            final StackId dynamicMfovAsTileStackId = prealignedStackId.withStackSuffix(dynamicMfovStackSuffix);

            if (mfovAsTileStackLists.isExistingStack(dynamicMfovAsTileStackId)) {
                LOG.info("buildMfovStackFunction: skipping build of {} because it already exists",
                         dynamicMfovAsTileStackId.toDevString());
            } else {
                final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                         prealignedStackId.getOwner(),
                                                                         prealignedStackId.getProject());

                builtStackId = MFOVAsTileStackClient.buildOneMFOVAsTileStack(stackWithAllZ,
                                                                             dataClient,
                                                                             mfovRenderScale,
                                                                             dynamicMfovStackSuffix);
            }

            return builtStackId;
        };

        final JavaRDD<StackId> rddBuiltStacks = rddPrealignedSfovStacks.map(buildMfovStackFunction);
        final List<StackId> builtStacks = rddBuiltStacks.collect();

        final long skippedCount = rddBuiltStacks.filter(Objects::isNull).count();
        final long builtCount = builtStacks.size() - skippedCount;

        LOG.info("buildDynamicMfovAsTileStacks: exit, built {} stack(s), skipped build of {} pre-existing stack(s)",
                 builtCount, skippedCount);
    }

    private static void buildRenderedMfovAsTileStacks(final JavaSparkContext sparkContext,
                                                      final MFOVAsTileStackLists mfovAsTileStackLists)
            throws IOException {

        LOG.info("buildRenderedMfovAsTileStacks: entry");

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final MFOVAsTileParameters mfovAsTile = mfovAsTileStackLists.getMfovAsTile();
        final String runTimestamp = new TileRenderParameters().getRunTimestamp();

        final List<JavaRenderTilesClientInfoForLayerMfov> layerMfovClientInfoList = new ArrayList<>();
        final List<StackId> renderedMfovStackList = new ArrayList<>();
        for (final StackWithZValues rawSfovStackWithAllZ : mfovAsTileStackLists.getRawSfovStacksWithAllZ()) {

            final StackId rawStackId = rawSfovStackWithAllZ.getStackId();
            final StackId dynamicMfovAsTileStackId = mfovAsTile.getDynamicMfovStackId(rawStackId);
            final StackId renderedMfovAsTileStackId = mfovAsTile.getRenderedMfovStackId(rawStackId);

            if (mfovAsTileStackLists.isExistingStack(renderedMfovAsTileStackId)) {

                LOG.info("buildRenderedMfovAsTileStacks: skipping build of {} because it already exists",
                         renderedMfovAsTileStackId.toDevString());

            } else {

                final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                         rawStackId.getOwner(),
                                                                         rawStackId.getProject());

                boolean isSetupNeeded = true;
                for (final Double z : rawSfovStackWithAllZ.getzValues()) {
                    final List<String> mfovNames = MultiProjectParameters.getSortedMFOVNamesForOneLayer(dataClient,
                                                                                                        rawStackId.getStack(),
                                                                                                        z);
                    for (final String mfovName : mfovNames) {

                        final JavaRenderTilesClientInfoForLayerMfov info =
                                new JavaRenderTilesClientInfoForLayerMfov(baseDataUrl,
                                                                          dynamicMfovAsTileStackId,
                                                                          new LayerMFOV(z, mfovName),
                                                                          mfovAsTile,
                                                                          runTimestamp);
                        layerMfovClientInfoList.add(info);

                        if (isSetupNeeded) {
                            info.setupHackStackAndStorage();
                            isSetupNeeded = false;
                            renderedMfovStackList.add(renderedMfovAsTileStackId);
                        }

                    }
                }
            }
        }

        if (! layerMfovClientInfoList.isEmpty()) {

            final int parallelism = Math.min(MAX_PARTITIONS_FOR_ONE_WEB_SERVER, layerMfovClientInfoList.size());

            LOG.info("buildRenderedMfovAsTileStacks: distributing rendering for {} layer MFOVs with parallelism {} (defaultParallelism={})",
                     layerMfovClientInfoList.size(), parallelism, sparkContext.defaultParallelism());

            final JavaRDD<JavaRenderTilesClientInfoForLayerMfov> rddRenderTiles = sparkContext.parallelize(layerMfovClientInfoList,
                                                                                                           parallelism);
            final Function<JavaRenderTilesClientInfoForLayerMfov, Integer> renderTilesFunction = JavaRenderTilesClientInfoForLayerMfov::renderTiles;
            final JavaRDD<Integer> rddRenderedTileCounts = rddRenderTiles.map(renderTilesFunction);

            final List<Integer> resultList = rddRenderedTileCounts.collect();

            LOG.info("buildRenderedMfovAsTileStacks: completed rendering for {} MFOV tiles", resultList.size());

            for (final StackId hackStackId : renderedMfovStackList) {
                final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                         hackStackId.getOwner(),
                                                                         hackStackId.getProject());
                dataClient.setStackState(hackStackId.getStack(), StackMetaData.StackState.COMPLETE);
            }
        }

        LOG.info("buildRenderedMfovAsTileStacks: exit");
    }

    private static void generateMfovAsTileMatches(final JavaSparkContext sparkContext,
                                                  final MFOVAsTileStackLists mfovAsTileStackLists)
            throws IOException {

        LOG.info("generateMfovAsTileMatches: entry");

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final List<MatchRunParameters> mfovMatchRunList = mfovAsTileStackLists.getMfovAsTile().buildMfovMatchRunList();

        for (final String owner : mfovAsTileStackLists.getOwners()) {

            final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl, owner, "not_used");
            final Set<String> existingMatchCollectionNames = renderDataClient.getOwnerMatchCollections().stream()
                    .map(mcmd -> mcmd.getCollectionId().getName())
                    .collect(Collectors.toSet());

            for (final String project : mfovAsTileStackLists.getProjectsWithOwner(owner)) {

                final MultiStagePointMatchClient matchClient = new MultiStagePointMatchClient();

                final List<String> projectStackNameList = new ArrayList<>();
                final List<StackWithZValues> listOfRenderedMfovStackLayersInProject = new ArrayList<>();

                for (final StackWithZValues stackWithZ : mfovAsTileStackLists.getRenderedMfovStacksWithAllZ(owner, project)) {

                    final StackId stackId = stackWithZ.getStackId();
                    final String matchCollectionName = stackId.getDefaultMatchCollectionId(false).getName();

                    if (existingMatchCollectionNames.contains(matchCollectionName)) {
                        LOG.info("generateMfovAsTileMatches: skipping {} match generation because it already exists",
                                 matchCollectionName);
                    } else {
                        projectStackNameList.add(stackId.getStack());
                        for (final Double z : stackWithZ.getzValues()) {
                            listOfRenderedMfovStackLayersInProject.add(new StackWithZValues(stackId,
                                                                                            Collections.singletonList(z)));
                        }
                    }
                }

                if (! projectStackNameList.isEmpty()) {

                    final MultiProjectParameters multiProject = new MultiProjectParameters();
                    multiProject.baseDataUrl = baseDataUrl;
                    multiProject.owner = owner;
                    multiProject.project = project;
                    multiProject.stackIdWithZ.stackNames = projectStackNameList;

                    matchClient.generatePairsAndMatchesForRunList(sparkContext,
                                                                  multiProject,
                                                                  listOfRenderedMfovStackLayersInProject,
                                                                  mfovMatchRunList);
                }

            }
        }

        LOG.info("generateMfovAsTileMatches: exit");
    }

    private static void removeCrossResinMfovAsTileMatches(final JavaSparkContext sparkContext,
                                                          final MFOVAsTileStackLists mfovAsTileStackLists) {

        final Double minCrossMatchPixelDistance = mfovAsTileStackLists.getMfovAsTile().getMinCrossMatchPixelDistance();
        if (minCrossMatchPixelDistance == null) {
            LOG.info("removeCrossResinMfovAsTileMatches: skipping because minCrossMatchPixelDistance is null");
            return;
        }

        // NOTE: Removal is run regardless of whether removal has been run before.

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final List<StackWithZValues> renderedMfovStacksWithAllZ = mfovAsTileStackLists.getRenderedMfovStacksWithAllZ();

        LOG.info("removeCrossResinMfovAsTileMatches: entry, distributing removal for {} stack(s)",
                 renderedMfovStacksWithAllZ.size());

        final JavaRDD<StackWithZValues> rddStacks = sparkContext.parallelize(renderedMfovStacksWithAllZ);

        final Function<StackWithZValues, String> removalFunction = stackWithZValues -> {

            LogUtilities.setupExecutorLog4j(stackWithZValues.getStackId().toDevString());

            final StackId stackId = stackWithZValues.getStackId();
            final MatchCollectionId matchCollectionId = stackId.getDefaultMatchCollectionId(false);
            final RenderDataClient matchClient = new RenderDataClient(baseDataUrl,
                                                                      matchCollectionId.getOwner(),
                                                                      matchCollectionId.getName());

            final MatchPairRemovalParameters matchPairRemovalParameters = new MatchPairRemovalParameters();
            matchPairRemovalParameters.minCrossMatchPixelDistance = minCrossMatchPixelDistance;
            matchPairRemovalParameters.maxNumberOfPairsToRemove = 1;

            final List<OrderedCanvasIdPair> removedPairs =
                    RemoveMatchPairClient.removeMatchPairsForCollection(matchClient, matchPairRemovalParameters);

            final String removedPairsString = removedPairs.stream()
                    .map(OrderedCanvasIdPair::toString)
                    .collect(Collectors.joining(", "));
            return stackId.toDevString()  + " had " + removedPairs.size() + " pairs removed: " + removedPairsString;
        };

        final List<String> removedPairsMessages = rddStacks.map(removalFunction).collect();

        for (final String msg : removedPairsMessages) {
            LOG.info("removeCrossResinMfovAsTileMatches: {}", msg);
        }

        LOG.info("removeCrossResinMfovAsTileMatches: exit");
    }

    private static void patchMissingMfovAsTileMatches(final JavaSparkContext sparkContext,
                                                      final MFOVAsTileStackLists mfovAsTileStackLists) {

        // NOTE: Patching is run for all stacks regardless of whether patching has been done before
        //       because only unconnected pairs are patched with each run.

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final List<StackWithZValues> renderedMfovStacksWithAllZ = mfovAsTileStackLists.getRenderedMfovStacksWithAllZ();

        LOG.info("patchMissingMfovAsTileMatches: entry, distributing patching for {} stack(s)",
                 renderedMfovStacksWithAllZ.size());

        final JavaRDD<StackWithZValues> rddPatchStacks = sparkContext.parallelize(renderedMfovStacksWithAllZ);

        final Function<StackWithZValues, Integer> patchFunction = stackWithZValues -> {

            LogUtilities.setupExecutorLog4j(stackWithZValues.getStackId().toDevString());

            // -------------------------------
            // 1. patch unconnected pairs in the stack

            final StackId stackId = stackWithZValues.getStackId();
            final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                     stackId.getOwner(),
                                                                     stackId.getProject());
            final MatchCollectionId matchCollectionId = stackId.getDefaultMatchCollectionId(false);

            final MFOVMontageMatchPatchParameters patchParameters = new MFOVMontageMatchPatchParameters();
            patchParameters.startPositionMatchWeight = 0.005;
            patchParameters.xyNeighborFactor = 0.6;

            final MFOVAsTileMontageMatchPatchClient patchClient = new MFOVAsTileMontageMatchPatchClient();

            final int patchedPairCount = patchClient.deriveAndSaveMatchesForUnconnectedSameLayerPairsInStack(dataClient,
                                                                                                             stackWithZValues,
                                                                                                             matchCollectionId,
                                                                                                             patchParameters);

            // -------------------------------
            // 2. verify that the patching worked by checking for any remaining unconnected pairs

            final ClusterCountClient.Parameters jcccp = new ClusterCountClient.Parameters();
            jcccp.multiProject = MultiProjectParameters.singleStackInstance(baseDataUrl, stackId);
            jcccp.tileCluster = new TileClusterParameters();

            final int zCount = stackWithZValues.getZCount();
            jcccp.tileCluster.maxSmallClusterSize = 0;
            jcccp.tileCluster.includeMatchesOutsideGroup = true;
            jcccp.tileCluster.maxLayersPerBatch = zCount + 1;
            jcccp.tileCluster.maxOverlapLayers = 6;

            final ClusterCountClient javaClusterCountClient = new ClusterCountClient(jcccp);

            final ConnectedTileClusterSummaryForStack summary =
                    javaClusterCountClient.findConnectedClustersForStack(stackWithZValues,
                                                                         matchCollectionId,
                                                                         dataClient,
                                                                         jcccp.tileCluster);
            final String countErrorString = summary.buildCountErrorString(1,
                                                                          0,
                                                                          0);
            if (! countErrorString.isEmpty()) {
                throw new IllegalStateException(countErrorString);
            }

            return patchedPairCount;
        };

        final JavaRDD<Integer> rddPatchedPairCounts = rddPatchStacks.map(patchFunction);

        final long totalNumberOfPatchedPairs =
                rddPatchedPairCounts.collect().stream().mapToInt(Integer::intValue).sum();

        LOG.info("patchMissingMfovAsTileMatches: patched {} unconnected MFOV pairs across all stacks",
                 totalNumberOfPatchedPairs);
    }

    private static void alignRenderedMfovAsTileStacks(final JavaSparkContext sparkContext,
                                                      final MFOVAsTileStackLists mfovAsTileStackLists)
            throws IOException {

        LOG.info("alignRenderedMfovAsTileStacks: entry");

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final MFOVAsTileParameters mfovAsTile = mfovAsTileStackLists.getMfovAsTile();
        final AffineBlockSolverSetup translationSetup =
                mfovAsTile.buildMfovAffineBlockSolverSetup(MFOVAsTileParameters.SolveType.TRANSLATION);
        final AffineBlockSolverSetup affineSetup =
                mfovAsTile.buildMfovAffineBlockSolverSetup(MFOVAsTileParameters.SolveType.AFFINE);

        final boolean deriveMatchCollectionNamesFromProject = false; // use standard stack-based match collection names
        final String matchSuffix = "";                               // without any suffix

        final List<AffineBlockSolverSetup> setupList = new ArrayList<>();
        for (final StackWithZValues renderedMfovStackWithAllZ : mfovAsTileStackLists.getRenderedMfovStacksWithAllZ()) {

            final StackId renderedMfovStackId = renderedMfovStackWithAllZ.getStackId();
            final StackId alignedMfovStackId =
                    renderedMfovStackId.withStackSuffix(mfovAsTile.getAlignedMfovStackSuffix());

            if (mfovAsTileStackLists.isExistingStack(alignedMfovStackId)) {
                LOG.info("alignRenderedMfovAsTileStacks: skipping alignment of {} because {} already exists",
                         renderedMfovStackId.toDevString(), alignedMfovStackId.toDevString());
            } else {
                setupList.add(translationSetup.buildPipelineClone(baseDataUrl,
                                                                  renderedMfovStackWithAllZ,
                                                                  deriveMatchCollectionNamesFromProject,
                                                                  matchSuffix));
                setupList.add(affineSetup.buildPipelineClone(baseDataUrl,
                                                             renderedMfovStackWithAllZ,
                                                             deriveMatchCollectionNamesFromProject,
                                                             matchSuffix));
            }
        }

        if (! setupList.isEmpty()) {
            LOG.info("alignRenderedMfovAsTileStacks: distributing alignment of {} stack(s)", setupList.size());
            final DistributedAffineBlockSolverClient affineBlockSolverClient = new DistributedAffineBlockSolverClient();
            affineBlockSolverClient.alignSetupList(sparkContext, setupList);
        }

        LOG.info("alignRenderedMfovAsTileStacks: exit");
    }

    private static void buildRoughSfovStacks(final JavaSparkContext sparkContext,
                                             final MFOVAsTileStackLists mfovAsTileStackLists) {

        LOG.info("buildRoughSfovStacks: entry");

        final String baseDataUrl = mfovAsTileStackLists.getBaseDataUrl();
        final MFOVAsTileParameters mfovAsTile = mfovAsTileStackLists.getMfovAsTile();
        final String roughSfovStackSuffix = mfovAsTile.getRoughSfovStackSuffix();
        final String renderedMfovStackSuffix = mfovAsTile.getRenderedMfovStackSuffixForRawSfovStack();
        final String alignedMfovStackSuffixForRaw = mfovAsTile.getAlignedMfovStackSuffixForRawSfovStack();

        final List<StackWithZValues> rawSfovStacksWithAllZ = mfovAsTileStackLists.getRawSfovStacksWithAllZ();
        final List<StackWithZValues> roughSfovStacksWithAllZ = mfovAsTileStackLists.getRoughSfovStacksWithAllZ();

        final List<StackWithZValues> rawSfovStacksNeedingRoughStack = new ArrayList<>();

        for (int i = 0; i < roughSfovStacksWithAllZ.size(); i++) {
            final StackWithZValues roughSfovStackWithAllZ = roughSfovStacksWithAllZ.get(i);
            final StackId roughSfovStackId = roughSfovStackWithAllZ.getStackId();
            if (mfovAsTileStackLists.isExistingStack(roughSfovStackId)) {
                LOG.info("buildRoughSfovStacks: skipping creation of {} because it already exists",
                         roughSfovStackId.toDevString());
            } else {
                rawSfovStacksNeedingRoughStack.add(rawSfovStacksWithAllZ.get(i));
            }
        }

        if (! rawSfovStacksNeedingRoughStack.isEmpty()) {

            LOG.info("buildRoughSfovStacks: distributing build of {} stack(s)",
                     rawSfovStacksNeedingRoughStack.size());

            final JavaRDD<StackWithZValues> rddAlignedStacks = sparkContext.parallelize(rawSfovStacksNeedingRoughStack);

            final Function<StackWithZValues, StackId> buildRoughStackFunction = stackWithAllZ -> {

                LogUtilities.setupExecutorLog4j(stackWithAllZ.getStackId().toDevString());

                final StackId rawSfovStackId = stackWithAllZ.getStackId();
                final StackId renderedMfovStackId = rawSfovStackId.withStackSuffix(renderedMfovStackSuffix);
                final StackId alignedMfovStackId = rawSfovStackId.withStackSuffix(alignedMfovStackSuffixForRaw);
                final StackId roughSfovStackId = rawSfovStackId.withStackSuffix(roughSfovStackSuffix);
                final String roughSfovStack = roughSfovStackId.getStack();

                final RenderDataClient workerDataClient = new RenderDataClient(baseDataUrl,
                                                                               rawSfovStackId.getOwner(),
                                                                               rawSfovStackId.getProject());

                final StackMetaData rawSfovStackMetaData = workerDataClient.getStackMetaData(rawSfovStackId.getStack());
                workerDataClient.setupDerivedStack(rawSfovStackMetaData, roughSfovStack);

                for (final Double z : stackWithAllZ.getzValues()) {
                    final ResolvedTileSpecCollection roughTiles = buildRoughTileSpecsForZ(workerDataClient,
                                                                                          rawSfovStackId.getStack(),
                                                                                          z,
                                                                                          renderedMfovStackId.getStack(),
                                                                                          alignedMfovStackId.getStack(),
                                                                                          mfovAsTile.getMfovRenderScale());
                    workerDataClient.saveResolvedTiles(roughTiles, roughSfovStack, z);
                }

                workerDataClient.setStackState(roughSfovStack, StackMetaData.StackState.COMPLETE);

                return roughSfovStackId;
            };

            final JavaRDD<StackId> rddBuiltStacks = rddAlignedStacks.map(buildRoughStackFunction);
            final List<StackId> builtStacks = rddBuiltStacks.collect();

            LOG.info("buildRoughSfovStacks: completed build of {} stack(s)", builtStacks.size());
        }

        LOG.info("buildRoughSfovStacks: exit");
    }

    @Nonnull
    private static ResolvedTileSpecCollection buildRoughTileSpecsForZ(final RenderDataClient dataClient,
                                                                      final String rawSfovStack,
                                                                      final double z,
                                                                      final String renderedMfovStack,
                                                                      final String alignedMfovStack,
                                                                      final double mfovAsTileRenderScale)
            throws IOException {

        // example SFOV tile spec:
        // {
        //   "tileId": "w60_magc0160_scan080_m0016_r44_s14",
        //   ...
        //   "transforms": {
        //     "type": "list",
        //     "specList": [
        //       {
        //         "className": "org.janelia.alignment.transform.ExponentialFunctionOffsetTransform",
        //         "dataString": "3.164065083689898,0.010223592506552219,0.0,0"
        //       },
        //       {
        //         "className": "mpicbg.trakem2.transform.AffineModel2D",
        //         "dataString": "1 0 0 1 67475.9840886624 95747.66454025533"
        //       }
        //     ]
        //   },
        //   ...
        // }
        final ResolvedTileSpecCollection sfovTiles = dataClient.getResolvedTiles(rawSfovStack, z);

        // example MFOV-as-tile spec:
        // {
        //   "tileId": "w60_s360_r00_gc_z075_m0018",
        //   ...
        //   "transforms": {
        //     "type": "list",
        //     "specList": [
        //       {
        //         "className": "mpicbg.trakem2.transform.TranslationModel2D",
        //         "dataString": "11978 24058"
        //       }
        //     ]
        //   },
        //   ...
        // }
        final ResolvedTileSpecCollection renderedMfovTiles = dataClient.getResolvedTiles(renderedMfovStack, z);
        final ResolvedTileSpecCollection alignedMfovTiles = dataClient.getResolvedTiles(alignedMfovStack, z);

        final Map<String, double[]> mfovToOffset = new HashMap<>();
        for (final String mfovTileId : renderedMfovTiles.getTileIds()) {
            final TileSpec renderedMfovTileSpec = renderedMfovTiles.getTileSpec(mfovTileId);
            final TileSpec alignedMfovTileSpec = alignedMfovTiles.getTileSpec(mfovTileId);
            final String simpleMfovName = mfovTileId.substring(mfovTileId.lastIndexOf('_') + 1); // e.g. "m0018"
            final double[] offset = new double[] {
                    (alignedMfovTileSpec.getMinX() - renderedMfovTileSpec.getMinX()) / mfovAsTileRenderScale,
                    (alignedMfovTileSpec.getMinY() - renderedMfovTileSpec.getMinY()) / mfovAsTileRenderScale
            };
            LOG.info("buildRoughTileSpecsForZ: renderedMfovStack={}, alignedMfovStack={}, mfovTileId={}, offset={}",
                     renderedMfovStack, alignedMfovStack, mfovTileId, Arrays.toString(offset));
            mfovToOffset.put(simpleMfovName, offset);
        }

        for (final TileSpec tileSpec : sfovTiles.getTileSpecs()) {
            final String tileId = tileSpec.getTileId();
            final String mfovName = MultiSemUtilities.getSimpleMfovForTileId(tileId);
            final LeafTransformSpec sfovTransformSpec = (LeafTransformSpec) tileSpec.getLastTransform();
            final double[] mfovOffset = mfovToOffset.get(mfovName);
            if (mfovOffset == null) {
                throw new IllegalStateException("no offset for tileId " + tileId + " with mfovName " + mfovName + " found in " + alignedMfovStack);
            }
            final LeafTransformSpec offsetTransformSpec = applyMfovOffset(mfovOffset,
                                                                          sfovTransformSpec);
            sfovTiles.addTransformSpecToTile(tileId,
                                             offsetTransformSpec,
                                             TransformApplicationMethod.REPLACE_LAST);
        }

        return sfovTiles;
    }

    private static LeafTransformSpec applyMfovOffset(final double[] mfovOffset,
                                                     final LeafTransformSpec sfovTransformSpec) {
        final String[] sfovStrings = sfovTransformSpec.getDataString().split(" ");
        final double x = Double.parseDouble(sfovStrings[4]) + mfovOffset[0];
        final double y = Double.parseDouble(sfovStrings[5]) + mfovOffset[1];
        return new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D",
                                     "1 0 0 1 " + x + " " + y);
    }

    // Serializable information that can be used to build RenderTilesClient instances in remote Spark workers
    public static class JavaRenderTilesClientInfoForLayerMfov
            implements Serializable {

        private final String baseDataUrl;
        private final StackId stackId;
        private final LayerMFOV layerMFOV;
        private final TileRenderParameters tileRender;

        public JavaRenderTilesClientInfoForLayerMfov(final String baseDataUrl,
                                                     final StackId stackId,
                                                     final LayerMFOV layerMFOV,
                                                     final MFOVAsTileParameters mfovAsTile,
                                                     final String runTimestamp) {
            this.baseDataUrl = baseDataUrl;
            this.stackId = stackId;
            this.layerMFOV = layerMFOV;
            final String hackStack = stackId.getStack() + mfovAsTile.getRenderedMfovStackSuffix();
            this.tileRender = TileRenderParameters.buildMfovAsTileVersion(mfovAsTile.getMfovRootDirectory(),
                                                                          runTimestamp,
                                                                          hackStack);
        }

        public org.janelia.render.client.tile.RenderTilesClient buildJavaRenderTilesClient(final String tileIdPattern) {
            return new org.janelia.render.client.tile.RenderTilesClient(
                    new RenderDataClient(baseDataUrl, stackId.getOwner(), stackId.getProject()),
                    stackId.getStack(),
                    tileRender.withTileIdPattern(tileIdPattern));
        }

        public void setupHackStackAndStorage()
                throws IOException {
            final org.janelia.render.client.tile.RenderTilesClient jClient = buildJavaRenderTilesClient(null);
            jClient.setupHackStackAsNeeded();
            jClient.setupStorageDirectories();
        }

        public int renderTiles()
                throws IOException {
            LogUtilities.setupExecutorLog4j(stackId.toDevString());
            final String tileIdPattern = ".*_" + layerMFOV.getSimpleMfovName(); // ".*_m0052"
            LOG.info("renderTiles: entry, stackId={}, layerMFOV={}, tileIdPattern={}",
                     stackId.toDevString(), layerMFOV, tileIdPattern);
            final org.janelia.render.client.tile.RenderTilesClient jClient =
                    buildJavaRenderTilesClient(tileIdPattern);
            jClient.renderTiles(Collections.singletonList(layerMFOV.getZ()));
            return 1;
        }
    }

    private static final int MAX_PARTITIONS_FOR_ONE_WEB_SERVER = 100000;

    private static final Logger LOG = LoggerFactory.getLogger(MFOVASTileClient.class);
}
