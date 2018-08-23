package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.ConsensusSetPairs;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.SplitCanvasHelper;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.HierarchicalTierRegions;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.TierDimensions;
import org.janelia.alignment.transform.ConsensusWarpFieldBuilder;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
import org.janelia.render.client.parameter.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating hierarchical alignment data.
 *
 * @author Eric Trautman
 */
public class HierarchicalAlignmentClient
        implements Serializable {

    public enum PipelineStep {
        SPLIT, MATCH, ALIGN, WARP
    }

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Name of the rough aligned tiles stack",
                required = true)
        public String stack;

        @Parameter(
                names = "--lastTier",
                description = "Last tier to generate"
        )
        public Integer lastTier = 1;

        @Parameter(
                names = "--maxPixelsPerDimension",
                description = "Scale each tier such that the number of pixels in the largest dimension is this number"
        )
        public Integer maxPixelsPerDimension = 2048;

        @Parameter(
                names = "--maxNumberOfTiers",
                description = "Maximum number of tiers in the hierarchy (or null to dynamically determine value)"
        )
        public Integer maxNumberOfTiers = null;

        @Parameter(
                names = "--maxCompleteAlignmentQuality",
                description = "Split stacks with quality values less than this maximum do not need to be aligned in subsequent tiers"
        )
        public Double maxCompleteAlignmentQuality;

        @Parameter(
                names = "--firstTier",
                description = "First tier to generate"
        )
        public Integer firstTier = 1;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Generate matches between layers with z values less than or equal to this distance from the current layer's z value"
        )
        public Integer zNeighborDistance = 2;

        @Parameter(
                names = "--renderWithFilter",
                description = "Render tiles using a filter for intensity correction",
                arity = 1)
        public boolean renderWithFilter = true;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill each canvas image with noise before rendering to improve point match derivation",
                arity = 1)
        public boolean fillWithNoise = true;

        @Parameter(
                names = "--channel",
                description = "Name of channel to use for alignment (omit if data is not multi-channel)"
        )
        public String channel;

        @Parameter(
                names = "--boxBaseDataUrl",
                description = "Base web service URL for boxes referenced in tiered split stacks (e.g. http://host[:port]/render-ws/v1).  If omitted, baseDataUrl will be used."
        )
        public String boxBaseDataUrl;

        @Parameter(
                names = "--minIntensity",
                description = "Minimum intensity for all tiered split stack canvas tiles"
        )
        public Double minIntensity = 0.0;

        @Parameter(
                names = "--maxIntensity",
                description = "Maximum intensity for all tiered split stack canvas tiles"
        )
        public Double maxIntensity = 255.0;

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @Parameter(
                names = { "--maxFeatureCacheGb" },
                description = "Maximum number of gigabytes of features to cache"
        )
        public Integer maxCacheGb = 2;

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

        @Parameter(
                names = "--solverScript",
                description = "Full path for solver"
        )
        public String solverScript = "/groups/flyTEM/flyTEM/matlab_compiled/bin/run_system_solve_affine_with_constraint_SL.sh";

        @Parameter(
                names = "--solverParametersTemplate",
                description = "Full path for solver parameters json file to be used as template for all solver runs",
                required = true)
        public String solverParametersTemplate;

        @Parameter(
                names = "--keepExisting",
                description = "Pipeline stage for which all prior existing results should be kept"
        )
        public PipelineStep keepExistingStep;

        @Parameter(
                names = "--consensusBuildMethod",
                description = "Method for building consensus warp fields"
        )
        public ConsensusWarpFieldBuilder.BuildMethod consensusBuildMethod = ConsensusWarpFieldBuilder.BuildMethod.SIMPLE;

        @Parameter(
                names = "--splitMethod",
                description = "Method used to divide each tier"
        )
        public TierDimensions.LayerSplitMethod splitMethod = TierDimensions.LayerSplitMethod.PRIME;

        String getBoxBaseDataUrl() {
            return boxBaseDataUrl == null ? renderWeb.baseDataUrl : boxBaseDataUrl;
        }

        boolean keepExisting(final PipelineStep step) {
            return (keepExistingStep != null) && (step.compareTo(keepExistingStep) <= 0);
        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final SparkConf sparkConf = new SparkConf().setAppName("HierarchicalAlignmentClient");
                final HierarchicalAlignmentClient client = new HierarchicalAlignmentClient(parameters,
                                                                                           sparkConf);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final JavaSparkContext sparkContext;
    private final RenderDataClient driverRoughRender;

    private final StackId roughTilesStackId;
    private final List<Double> zValues;
    private int currentTier;
    private String tierProject;
    private StackId tierParentStackId;
    private List<HierarchicalStack> tierStacks;
    private TierDimensions tierDimensions;
    private HierarchicalTierRegions priorTierRegions;
    private RenderDataClient driverTierRender;

    HierarchicalAlignmentClient(final Parameters parameters,
                                final SparkConf sparkConf) throws IllegalArgumentException {
        this.parameters = parameters;
        this.sparkContext = new JavaSparkContext(sparkConf);

        LogUtilities.logSparkClusterInfo(sparkContext);

        this.driverRoughRender = parameters.renderWeb.getDataClient();

        this.roughTilesStackId = new StackId(parameters.renderWeb.owner,
                                             parameters.renderWeb.project,
                                             parameters.stack);
        this.zValues = new ArrayList<>();

        this.tierStacks = new ArrayList<>();
        this.tierDimensions = null;
        this.priorTierRegions = null;
    }

    public void run() throws IOException, URISyntaxException {

        this.zValues.addAll(driverRoughRender.getStackZValues(parameters.stack));

        final StackMetaData roughStackMetaData = driverRoughRender.getStackMetaData(roughTilesStackId.getStack());

        if ((parameters.firstTier > 1) && (parameters.maxCompleteAlignmentQuality != null)) {

            final int priorTier = parameters.firstTier - 1;
            final StackId parentTilesStackId = HierarchicalStack.deriveParentTierStackId(roughTilesStackId, priorTier);
            final StackMetaData parentStackMetaData = driverRoughRender.getStackMetaData(parentTilesStackId.getStack());

            setupForTier(priorTier, roughStackMetaData, parentStackMetaData);
            updateExistingDerivedData();

            this.priorTierRegions =
                    new HierarchicalTierRegions(parentStackMetaData.getStats().getStackBounds(),
                                                tierStacks,
                                                tierDimensions,
                                                parameters.maxCompleteAlignmentQuality);
        }

        for (int tier = parameters.firstTier; tier <= parameters.lastTier; tier++) {

            final StackId parentTilesStackId = HierarchicalStack.deriveParentTierStackId(roughTilesStackId, tier);
            final StackMetaData parentStackMetaData = driverRoughRender.getStackMetaData(parentTilesStackId.getStack());

            setupForTier(tier, roughStackMetaData, parentStackMetaData);
            createStacksForTier(parentStackMetaData);
            generateMatchesForTier();
            alignTier();
            createWarpStackForTier();

            // after the first requested tier is processed,
            // remove the keepExisting flag so that subsequent tiers are processed in their entirety
            parameters.keepExistingStep = null;

            if ((tier < parameters.lastTier) && (parameters.maxCompleteAlignmentQuality != null)) {
                this.priorTierRegions =
                        new HierarchicalTierRegions(parentStackMetaData.getStats().getStackBounds(),
                                                    tierStacks,
                                                    tierDimensions,
                                                    parameters.maxCompleteAlignmentQuality);
            }

        }

        sparkContext.stop();
    }

    private void setupForTier(final int tier,
                              final StackMetaData roughStackMetaData,
                              final StackMetaData parentStackMetaData) {

        LOG.info("setupForTier: entry, tier={}", tier);

        this.currentTier = tier;
        this.tierProject = HierarchicalStack.deriveProjectForTier(roughTilesStackId, currentTier);
        this.tierParentStackId = parentStackMetaData.getStackId();
        final Bounds roughStackBounds = roughStackMetaData.getStats().getStackBounds();

        final int cellWidth = parameters.maxPixelsPerDimension;
        final int cellHeight = parameters.maxPixelsPerDimension;

        final List<TierDimensions> tierDimensionsList = TierDimensions.buildTierDimensionsList(parameters.splitMethod,
                                                                                               roughStackBounds,
                                                                                               cellWidth,
                                                                                               cellHeight,
                                                                                               null);

        this.tierDimensions = tierDimensionsList.get(currentTier);
        this.tierStacks = this.tierDimensions.getSplitStacks(roughTilesStackId, currentTier);

        if (this.tierStacks.size() == 0) {
            throw new IllegalStateException("no split stacks for tier " + tier + " of " + this.roughTilesStackId);
        }

        if (this.priorTierRegions != null) {
            this.tierStacks = this.priorTierRegions.getIncompleteTierStacks(this.tierStacks);
        }

        this.driverTierRender = new RenderDataClient(this.parameters.renderWeb.baseDataUrl,
                                                     this.parameters.renderWeb.owner,
                                                     this.tierProject);

        LOG.info("setupForTier: exit");
    }

    private void updateExistingDerivedData() throws IOException {

        final Set<StackId> existingTierProjectStackIds = new HashSet<>(driverTierRender.getProjectStacks());

        for (final HierarchicalStack splitStack : tierStacks) {
            final StackId splitStackId = splitStack.getSplitStackId();
            if (existingTierProjectStackIds.contains(splitStackId)) {
                final StackMetaData existingMetaData = driverTierRender.getStackMetaData(splitStackId.getStack());
                splitStack.updateDerivedData(existingMetaData.getHierarchicalData());
            }
        }

    }



    private void createStacksForTier(final StackMetaData parentStackMetaData)
            throws IOException {

        LOG.info("createStacksForTier: entry, tier={}", currentTier);

        final ProcessTimer timer = new ProcessTimer();

        final String versionNotes = "tier " + currentTier + " stack derived from " + parentStackMetaData.getStackId();

        final Set<StackId> existingTierProjectStackIds = new HashSet<>();
        if (parameters.keepExisting(PipelineStep.SPLIT)) {
            existingTierProjectStackIds.addAll(driverTierRender.getProjectStacks());
            LOG.info("createStacksForTier: found {} existing {} project stacks",
                     existingTierProjectStackIds.size(), tierProject);
        } else {
            driverTierRender.deleteAllStacksInProject();
        }

        for (final HierarchicalStack splitStack : tierStacks) {
            final StackId splitStackId = splitStack.getSplitStackId();
            if (existingTierProjectStackIds.contains(splitStackId)) {
                final StackMetaData existingMetaData = driverTierRender.getStackMetaData(splitStackId.getStack());
                final HierarchicalStack storedHierarchicalData = existingMetaData.getHierarchicalData();
                splitStack.updateDerivedData(storedHierarchicalData);
            }
        }

        final String versionTimestamp = String.valueOf(new Date().getTime());
        final StringBuilder boxUrlSuffix = new StringBuilder("/tiff-image?maxTileSpecsToRender=999999&v=").append(versionTimestamp);
        if (parameters.channel != null) {
            boxUrlSuffix.append("&channels=").append(parameters.channel);
        }
        boxUrlSuffix.append("&name=z");

        final JavaRDD<HierarchicalStack> rddTierStacks = sparkContext.parallelize(tierStacks);

        final Function<HierarchicalStack, Integer> createStacksFunction =
                new HierarchicalStackCreationFunction(parameters.renderWeb.baseDataUrl,
                                                      parameters.renderWeb.owner,
                                                      tierProject,
                                                      parentStackMetaData.getCurrentVersion(),
                                                      versionNotes,
                                                      currentTier,
                                                      existingTierProjectStackIds,
                                                      zValues,
                                                      parameters.channel,
                                                      parameters.minIntensity,
                                                      parameters.maxIntensity,
                                                      parameters.getBoxBaseDataUrl(),
                                                      boxUrlSuffix.toString());

        final JavaRDD<Integer> rddTileCounts = rddTierStacks.map(createStacksFunction);

        final List<Integer> tileCountList = rddTileCounts.collect();

        LOG.info("createStacksForTier: counting results");

        long total = 0;
        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("createStacksForTier: exit, created {} tile specs in {} stacks for tier {} in {} seconds",
                 total, tierStacks.size(), currentTier, timer.getElapsedSeconds());
    }

    private RenderableCanvasIdPairs getRenderablePairsForStack(final HierarchicalStack tierStack) {

        final List<OrderedCanvasIdPair> neighborPairs = tierStack.getNeighborPairs(zValues,
                                                                                   parameters.zNeighborDistance);

        final StackId parentTierStackId = tierStack.getParentTierStackId();
        final String renderUrlTemplate =
                String.format("{baseDataUrl}/owner/%s/project/%s/stack/%s/z/{groupId}/box/{id}/render-parameters",
                              parentTierStackId.getOwner(),
                              parentTierStackId.getProject(),
                              parentTierStackId.getStack());

        final RenderableCanvasIdPairs renderableCanvasIdPairs = new RenderableCanvasIdPairs(renderUrlTemplate,
                                                                                            neighborPairs);
        LOG.info("getRenderablePairsForStack: exit, returning {} pairs with template {}",
                 renderableCanvasIdPairs.size(), renderUrlTemplate);

        return renderableCanvasIdPairs;
    }

    private FeatureStorageParameters getFeatureStorageParameters() {
        final FeatureStorageParameters storageParameters = new FeatureStorageParameters();
        storageParameters.maxCacheGb = parameters.maxCacheGb;
        return storageParameters;
    }

    private void generateMatchesForTier()
            throws IOException, URISyntaxException {

        LOG.info("generateMatchesForTier: entry");

        final RenderDataClient driverMatchClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                        parameters.renderWeb.owner,
                                                                        "not_applicable");

        final Map<String, Long> existingMatchPairCounts = getExistingMatchPairCounts(driverMatchClient);

        if (parameters.keepExisting(PipelineStep.MATCH)) {
            updateSavedMatchPairCounts(existingMatchPairCounts);
        } else {
            deleteExistingMatchDataForTier(driverMatchClient, existingMatchPairCounts);
        }

        final FeatureRenderParameters featureRenderParameters = new FeatureRenderParameters();
        featureRenderParameters.fillWithNoise = parameters.fillWithNoise;
        featureRenderParameters.renderWithFilter = parameters.renderWithFilter;
        featureRenderParameters.renderWithoutMask = false; // always include masks because we are rendering scapes
        featureRenderParameters.renderScale = 1.0; // always render full scale because canvases are already scaled down

        final FeatureRenderClipParameters emptyClipParameters = new FeatureRenderClipParameters(); // no need to clip scapes

        final long potentialPairsPerStack = getPotentialPairsPerStack(zValues.size(), parameters.zNeighborDistance);
        final long totalPotentialPairs = potentialPairsPerStack * tierStacks.size();

        LOG.info("generateMatchesForTier: defaultParallelism={}, potentialPairsPerStack={}, totalPotentialPairs={}",
                 sparkContext.defaultParallelism(), potentialPairsPerStack, totalPotentialPairs);

        if ((totalPotentialPairs < 1000) ||
            ( (potentialPairsPerStack < sparkContext.defaultParallelism()) && (totalPotentialPairs < 100000) )) {

            // TODO: make sure potentialPairsPerStack ... is the check we want to use single batch processing
            generateTierMatchesInOneBatch(featureRenderParameters, emptyClipParameters, driverMatchClient);

        } else {

            generateTierMatchesByStack(featureRenderParameters, emptyClipParameters);

        }

        LOG.info("generateMatchesForTier: exit");
    }

    private Map<String, Long> getExistingMatchPairCounts(final RenderDataClient driverMatchClient)
            throws IOException {

        final Map<String, Long> existingMatchCollectionPairCounts = new HashMap<>();
        for (final MatchCollectionMetaData metaData : driverMatchClient.getOwnerMatchCollections()) {
            existingMatchCollectionPairCounts.put(metaData.getCollectionId().getName(), metaData.getPairCount());
        }

        return existingMatchCollectionPairCounts;
    }

    private void updateSavedMatchPairCounts(final Map<String, Long> existingMatchCollectionPairCounts) {

        for (final HierarchicalStack tierStack : tierStacks) {
            final String matchCollectionName = tierStack.getMatchCollectionId().getName();
            // NOTE: will set count to null if match collection does not exist
            tierStack.setSavedMatchPairCount(existingMatchCollectionPairCounts.get(matchCollectionName));
        }
    }

    private void deleteExistingMatchDataForTier(final RenderDataClient driverMatchClient,
                                                final Map<String, Long> existingMatchCollectionPairCounts)
            throws IOException {

        for (final HierarchicalStack tierStack : tierStacks) {

            final String matchCollectionName = tierStack.getMatchCollectionId().getName();
            if (existingMatchCollectionPairCounts.containsKey(matchCollectionName)) {
                driverMatchClient.deleteMatchCollection(matchCollectionName);
            }

            if (! tierStack.requiresMatchDerivation()) {
                tierStack.setSavedMatchPairCount(null);
                persistHierarchicalData(tierStack);
            }

        }
    }

    private void persistHierarchicalData(final HierarchicalStack tierStack)
            throws IOException {
        driverTierRender.setHierarchicalData(tierStack.getSplitStackId().getStack(), tierStack);
    }

    private void generateTierMatchesInOneBatch(final FeatureRenderParameters featureRenderParameters,
                                               final FeatureRenderClipParameters emptyClipParameters,
                                               final RenderDataClient driverMatchClient)
            throws IOException, URISyntaxException {

        LOG.info("generateTierMatchesInOneBatch: entry");

        final MultiCollectionMatchStorageFunction matchStorageFunction =
                new MultiCollectionMatchStorageFunction(parameters.renderWeb.baseDataUrl,
                                                        parameters.renderWeb.owner);

        RenderableCanvasIdPairs renderableCanvasIdPairs = null;

        for (final HierarchicalStack tierStack : tierStacks) {

            if (tierStack.requiresMatchDerivation()) {

                final String matchCollectionName = tierStack.getMatchCollectionId().getName();

                int fromIndex = 0;
                if (renderableCanvasIdPairs == null) {
                    renderableCanvasIdPairs = getRenderablePairsForStack(tierStack);
                } else {
                    fromIndex = renderableCanvasIdPairs.size();
                    renderableCanvasIdPairs.addNeighborPairs(tierStack.getNeighborPairs(zValues,
                                                                                        parameters.zNeighborDistance));
                }

                final List<OrderedCanvasIdPair> tierPairs =
                        renderableCanvasIdPairs.getNeighborPairs().subList(fromIndex,
                                                                           renderableCanvasIdPairs.size());
                for (final OrderedCanvasIdPair tierPair : tierPairs) {
                    matchStorageFunction.mapPIdToCollection(tierPair.getP().getId(), matchCollectionName);
                }

            }
        }

        if (renderableCanvasIdPairs != null) {

            LOG.info("generateTierMatchesInOneBatch: generating matches for {} pairs", renderableCanvasIdPairs.size());

            // TODO: do match parameters need to be tuned per tier?

            final long savedMatchPairCount =
                    SIFTPointMatchClient.generateMatchesForPairs(sparkContext,
                                                                 renderableCanvasIdPairs,
                                                                 parameters.renderWeb.baseDataUrl,
                                                                 featureRenderParameters,
                                                                 emptyClipParameters,
                                                                 parameters.featureExtraction,
                                                                 getFeatureStorageParameters(),
                                                                 parameters.matchDerivation,
                                                                 matchStorageFunction);

            LOG.info("generateTierMatchesInOneBatch: saved matches for {} pairs", savedMatchPairCount);

            // updated saved match pair counts for all tier stacks
            final Map<String, Long> existingMatchPairCounts = getExistingMatchPairCounts(driverMatchClient);
            for (final HierarchicalStack tierStack : tierStacks) {

                if (tierStack.requiresMatchDerivation()) {
                    long matchPairCount = 0;
                    final String collectionName = tierStack.getMatchCollectionId().getName();
                    if (existingMatchPairCounts.containsKey(collectionName)) {
                        matchPairCount = existingMatchPairCounts.get(collectionName);
                    }
                    tierStack.setSavedMatchPairCount(matchPairCount);
                    persistHierarchicalData(tierStack);
                }

            }

        }

        LOG.info("generateTierMatchesInOneBatch: exit");
    }

    private void generateTierMatchesByStack(final FeatureRenderParameters featureRenderParameters,
                                            final FeatureRenderClipParameters emptyClipParameters)
            throws IOException, URISyntaxException {

        LOG.info("generateTierMatchesByStack: entry");

        for (final HierarchicalStack tierStack : tierStacks) {

            if (tierStack.requiresMatchDerivation()) {

                final MatchCollectionId matchCollectionId = tierStack.getMatchCollectionId();

                LOG.info("generateTierMatchesByStack: generating {}", matchCollectionId.getName());

                final MatchStorageFunction matchStorageFunction =
                        new MatchStorageFunction(parameters.renderWeb.baseDataUrl,
                                                 matchCollectionId.getOwner(),
                                                 matchCollectionId.getName());

                // TODO: do match parameters need to be tuned per tier?

                final long savedMatchPairCount =
                        SIFTPointMatchClient.generateMatchesForPairs(sparkContext,
                                                                     getRenderablePairsForStack(tierStack),
                                                                     parameters.renderWeb.baseDataUrl,
                                                                     featureRenderParameters,
                                                                     emptyClipParameters,
                                                                     parameters.featureExtraction,
                                                                     getFeatureStorageParameters(),
                                                                     parameters.matchDerivation,
                                                                     matchStorageFunction);

                tierStack.setSavedMatchPairCount(savedMatchPairCount);
                persistHierarchicalData(tierStack);

            }
        }

        LOG.info("generateTierMatchesByStack: exit");
    }

    private void deriveSplitMatchesForConsensusSetCanvases(final List<HierarchicalStack> stacksToAlign)
            throws IOException {

        for (final HierarchicalStack tierStack : stacksToAlign) {

            final MatchCollectionId matchCollectionId = tierStack.getMatchCollectionId();

            final RenderDataClient driverMatchClient = new RenderDataClient(this.parameters.renderWeb.baseDataUrl,
                                                                            matchCollectionId.getOwner(),
                                                                            matchCollectionId.getName());

            final List<String> multiConsensusGroupIds = driverMatchClient.getMatchMultiConsensusPGroupIds();

            LOG.info("deriveSplitMatchesForConsensusSetCanvases: found {} multi consensus group ids in {}",
                     multiConsensusGroupIds.size(), matchCollectionId.getName());

            if (multiConsensusGroupIds.size() > 0) {

                final Set<CanvasMatches> multiConsensusPairs = new TreeSet<>();
                for (final String groupId : multiConsensusGroupIds) {
                    multiConsensusPairs.addAll(driverMatchClient.getMatchesOutsideGroup(groupId));
                }

                final ConsensusSetPairs consolidatedPairs = new ConsensusSetPairs(multiConsensusPairs);

                LOG.info("deriveSplitMatchesForConsensusSetCanvases: consolidated pair info is {}", consolidatedPairs);

                final List<CanvasMatches> derivedMatchPairs = consolidatedPairs.getDerivedPairs();

                final SplitCanvasHelper splitCanvasHelper = new SplitCanvasHelper();
                splitCanvasHelper.trackSplitCanvases(derivedMatchPairs);

                for (final String groupId : multiConsensusGroupIds) {
                    driverMatchClient.deleteMatchesOutsideGroup(groupId);
                }

                driverMatchClient.saveMatches(derivedMatchPairs);

                LOG.info("deriveSplitMatchesForConsensusSetCanvases: allocated matches to {} split canvases",
                         splitCanvasHelper.getCanvasCount());

                final String tierStackName = tierStack.getSplitStackId().getStack();
                driverTierRender.setStackState(tierStackName, StackMetaData.StackState.LOADING);

                for (final Double z : splitCanvasHelper.getSortedZValues()) {
                    final ResolvedTileSpecCollection resolvedTiles = driverTierRender.getResolvedTiles(tierStackName, z);
                    splitCanvasHelper.addDerivedTileSpecsToCollection(z, resolvedTiles);
                    resolvedTiles.removeTileSpecs(splitCanvasHelper.getOriginalIdsForZ(z));
                    driverTierRender.deleteStack(tierStackName, z);
                    driverTierRender.saveResolvedTiles(resolvedTiles, tierStackName, z);
                }

                driverTierRender.setStackState(tierStackName, StackMetaData.StackState.COMPLETE);

                if (consolidatedPairs.hasSplitGroups()) {
                    tierStack.setSplitGroupIds(consolidatedPairs.getSplitGroupIds());
                    persistHierarchicalData(tierStack);
                }
            }

        }
    }

    private void alignTier()
            throws IOException {

        LOG.info("alignTier: entry");

        final List<HierarchicalStack> stacksWithMatches =
                tierStacks.stream().
                        filter(HierarchicalStack::hasMatchPairs).
                        collect(Collectors.toList());

        final List<HierarchicalStack> stacksToAlign;
        if (parameters.keepExisting(PipelineStep.ALIGN)) {

            stacksToAlign =
                    stacksWithMatches.stream().
                            filter(HierarchicalStack::requiresAlignment).
                            collect(Collectors.toList());

            LOG.info("alignTier: {} out of {} stacks with matches also have alignment results",
                     (stacksWithMatches.size() - stacksToAlign.size()), stacksWithMatches.size());

        } else {
            stacksToAlign = stacksWithMatches;
        }

        if (stacksToAlign.size() > 0) {

            deriveSplitMatchesForConsensusSetCanvases(stacksToAlign);

            // broadcast EM_aligner tool to ensure that solver is run serially on each node
            final EMAlignerTool solver = new EMAlignerTool(new File(parameters.solverScript),
                                                           new File(parameters.solverParametersTemplate));
            final Broadcast<EMAlignerTool> broadcastEMAlignerTool = sparkContext.broadcast(solver);

            final HierarchicalTierSolveFunction solveStacksFunction =
                    new HierarchicalTierSolveFunction(parameters.renderWeb.baseDataUrl,
                                                      parameters.zNeighborDistance,
                                                      broadcastEMAlignerTool);

            // remove any pre-existing alignment results ...
            for (final HierarchicalStack tierStack : stacksToAlign) {
                driverTierRender.deleteStack(tierStack.getAlignedStackId().getStack(), null);
            }

            final JavaRDD<HierarchicalStack> rddTierStacksToAlign = sparkContext.parallelize(stacksToAlign);

            final JavaRDD<HierarchicalStack> rddTierStacksAfterAlignment =
                    rddTierStacksToAlign.map(solveStacksFunction);

            final List<HierarchicalStack> tierStacksAfterAlignment = rddTierStacksAfterAlignment.collect();

            LOG.info("alignTier: processing results");

            final Map<String, HierarchicalStack> nameToUpdatedStackMap =
                    new HashMap<>(tierStacksAfterAlignment.size() * 2);

            for (final HierarchicalStack tierStack : tierStacksAfterAlignment) {
                final String tierStackName = tierStack.getSplitStackId().getStack();
                LOG.info("alignTier: stack {} has alignment quality {}",
                         tierStackName, tierStack.getAlignmentQuality());
                persistHierarchicalData(tierStack);
                nameToUpdatedStackMap.put(tierStackName, tierStack);
            }

            // update local hierarchical stack data with alignment metadata
            HierarchicalStack tierStack;
            HierarchicalStack updatedStack;
            for (int i = 0; i < tierStacks.size(); i++) {
                tierStack = tierStacks.get(i);
                updatedStack = nameToUpdatedStackMap.get(tierStack.getSplitStackId().getStack());
                if (updatedStack != null) {
                    tierStacks.set(i, updatedStack);
                }
            }

        } else {
            LOG.info("alignTier: all aligned stacks have already been generated");
        }

        LOG.info("alignTier: exit");
    }

    private void createWarpStackForTier()
            throws IOException {

        LOG.info("createWarpStackForTier: entry");

        final ProcessTimer timer = new ProcessTimer();

        final Set<StackId> existingRoughProjectStackIds = new HashSet<>(driverRoughRender.getProjectStacks());

        final StackId warpStackId = HierarchicalStack.deriveWarpStackIdForTier(roughTilesStackId, currentTier);

        boolean generateWarpStack = true;
        if (existingRoughProjectStackIds.contains(warpStackId) &&
            parameters.keepExisting(PipelineStep.WARP)) {
            generateWarpStack = false;
        }

        if (generateWarpStack) {

            // remove any existing warp stack results
            driverRoughRender.deleteStack(warpStackId.getStack(), null);

            final StackMetaData roughTilesStackMetaData =
                    driverRoughRender.getStackMetaData(roughTilesStackId.getStack());

            driverRoughRender.setupDerivedStack(roughTilesStackMetaData, warpStackId.getStack());

            final String projectForTier = this.tierProject;

            final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);
            final HierarchicalWarpFieldStackFunction warpFieldStackFunction
                    = new HierarchicalWarpFieldStackFunction(parameters.renderWeb.baseDataUrl,
                                                             parameters.renderWeb.owner,
                                                             currentTier,
                                                             projectForTier,
                                                             tierParentStackId,
                                                             warpStackId.getStack(),
                                                             parameters.consensusBuildMethod);

            final JavaRDD<Integer> rddTileCounts = rddZValues.map(warpFieldStackFunction);

            final List<Integer> tileCountList = rddTileCounts.collect();

            LOG.info("createWarpStackForTier: counting results");

            long total = 0;
            for (final Integer tileCount : tileCountList) {
                total += tileCount;
            }

            LOG.info("createWarpStackForTier: added {} tile specs to {}", total, warpStackId);

            driverRoughRender.setStackState(warpStackId.getStack(), StackMetaData.StackState.COMPLETE);
        }

        LOG.info("createWarpStackForTier: exit, processing took {} seconds", timer.getElapsedSeconds());
    }

    static long getPotentialPairsPerStack(final int numberOfLayers,
                                          final int zNeighborDistance) {
        final long potentialPairsPerStack;
        if (zNeighborDistance >= numberOfLayers) {
            potentialPairsPerStack = getTriangularNumber(numberOfLayers - 1);
        } else {
            potentialPairsPerStack = (numberOfLayers * zNeighborDistance) - getTriangularNumber(zNeighborDistance);
        }
        return potentialPairsPerStack;
    }

    private static long getTriangularNumber(final int n) {
        return n * (n + 1) / 2;
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalAlignmentClient.class);
}
