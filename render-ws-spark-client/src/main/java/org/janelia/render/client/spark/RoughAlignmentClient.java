package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.TierZeroStack;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.render.client.parameter.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for roughly aligning montage scapes.
 *
 * TODO: refactor so that common HierarchicalAlignmentClient components are shared as much as possible
 *
 * @author Eric Trautman
 */
public class RoughAlignmentClient
        implements Serializable {

    public enum PipelineStep {
        SPLIT, MATCH, ALIGN, ROUGH
    }

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--montageStack",
                description = "Name of the montage tiles stack",
                required = true)
        public String montageStack;

        @Parameter(
                names = "--roughStack",
                description = "Name of the (result) rough tiles stack",
                required = true)
        public String roughStack;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Generate matches between layers with z values less than or equal to this distance from the current layer's z value"
        )
        public Integer zNeighborDistance = 2;

        @Parameter(
                names = "--scapeRenderScale",
                description = "Render montage layer scapes at this scale"
        )
        public Double scapeRenderScale;

        @Parameter(
                names = "--maxPixelsPerScape",
                description = "Set montage layer scape render scale based upon this pixel maximum (ignored if --scapeRenderScale is specified)"
        )
        public Integer maxPixelsPerScape = 5_000_000;

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
                final RoughAlignmentClient client = new RoughAlignmentClient(parameters,
                                                                             sparkConf);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final JavaSparkContext sparkContext;
    private final RenderDataClient driverMontageRender;

    private final StackId montageTilesStackId;
    private final List<Double> zValues;

    private TierZeroStack tierZeroStack;
    private String tierZeroProject;
    private RenderDataClient driverTierRender;

    private RoughAlignmentClient(final Parameters parameters,
                                 final SparkConf sparkConf) throws IllegalArgumentException {
        this.parameters = parameters;
        this.sparkContext = new JavaSparkContext(sparkConf);

        LogUtilities.logSparkClusterInfo(sparkContext);

        this.driverMontageRender = parameters.renderWeb.getDataClient();

        this.montageTilesStackId = new StackId(parameters.renderWeb.owner,
                                               parameters.renderWeb.project,
                                               parameters.montageStack);
        this.zValues = new ArrayList<>();
    }

    public void run() throws IOException, URISyntaxException {

        final StackMetaData montageStackMetaData = driverMontageRender.getStackMetaData(parameters.montageStack);

        setupTierZeroStack(montageStackMetaData);
        createTierZeroStack(montageStackMetaData);
        generateMatchesForTier();
        alignTier();
        createRoughStack();

        sparkContext.stop();
    }

    private void setupTierZeroStack(final StackMetaData montageStackMetaData)
            throws IOException {

        LOG.info("setupTierZeroStack: entry");

        final List<SectionData> sectionDataList = driverMontageRender.getStackSectionData(parameters.montageStack,
                                                                                          null,
                                                                                          null);

        final Map<Double, Bounds> zToBoundsMap = new HashMap<>(sectionDataList.size() * 2);
        double maxDeltaX = 0.0;
        double maxDeltaY = 0.0;
        Double z;
        Bounds layerBounds;
        for (final SectionData sectionData : sectionDataList) {
            z = sectionData.getZ();
            layerBounds = zToBoundsMap.get(z);
            if (layerBounds == null) {
                layerBounds = new Bounds(sectionData.getMinX(),
                                         sectionData.getMinY(),
                                         z,
                                         sectionData.getMaxX(),
                                         sectionData.getMaxY(),
                                         z);
            } else {
                layerBounds = new Bounds(Math.min(sectionData.getMinX(), layerBounds.getMinX()),
                                         Math.min(sectionData.getMinY(), layerBounds.getMinY()),
                                         z,
                                         Math.max(sectionData.getMaxX(), layerBounds.getMaxX()),
                                         Math.max(sectionData.getMaxY(), layerBounds.getMaxY()),
                                         z);
            }

            zToBoundsMap.put(z, layerBounds);
            maxDeltaX = Math.max(layerBounds.getDeltaX(), maxDeltaX);
            maxDeltaY = Math.max(layerBounds.getDeltaY(), maxDeltaY);
        }

        zValues.addAll(zToBoundsMap.keySet());
        Collections.sort(zValues);

        final double scapeRenderScale;
        if (parameters.scapeRenderScale == null) {
            final double maxFullScalePixelsPerLayer = maxDeltaX * maxDeltaY;
            scapeRenderScale = Math.sqrt(parameters.maxPixelsPerScape / maxFullScalePixelsPerLayer);
        } else {
            scapeRenderScale = parameters.scapeRenderScale;
        }

        LOG.info("setupTierZeroStack: using scapeRenderScale {}", scapeRenderScale);

        final Bounds montageStackBounds = montageStackMetaData.getStats().getStackBounds();
        final Bounds tierZeroFullScaleBounds = new Bounds(      0.0,       0.0, montageStackBounds.getMinZ(),
                                                          maxDeltaX, maxDeltaY, montageStackBounds.getMaxZ());

        tierZeroStack = new TierZeroStack(montageTilesStackId,
                                          scapeRenderScale,
                                          tierZeroFullScaleBounds,
                                          zToBoundsMap);

        tierZeroProject = tierZeroStack.getSplitStackId().getProject();
    }

    private void createTierZeroStack(final StackMetaData montageStackMetaData)
            throws IOException {

        LOG.info("createTierZeroStack: entry");

        final ProcessTimer timer = new ProcessTimer();

        driverTierRender = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                parameters.renderWeb.owner,
                                                tierZeroProject);

        final String versionNotes = "tier 0 stack derived from " + montageTilesStackId;

        final Set<StackId> existingTierProjectStackIds = new HashSet<>();
        if (parameters.keepExisting(PipelineStep.SPLIT)) {
            existingTierProjectStackIds.addAll(driverTierRender.getProjectStacks());
            LOG.info("createTierZeroStack: found {} existing {} project stacks",
                     existingTierProjectStackIds.size(), tierZeroProject);
        } else {
            driverTierRender.deleteAllStacksInProject();
        }

        final StackId splitStackId = tierZeroStack.getSplitStackId();
        if (existingTierProjectStackIds.contains(splitStackId)) {
            final StackMetaData existingMetaData = driverTierRender.getStackMetaData(splitStackId.getStack());
            final HierarchicalStack storedHierarchicalData = existingMetaData.getHierarchicalData();
            tierZeroStack.updateDerivedData(storedHierarchicalData);
        }

        final String versionTimestamp = String.valueOf(new Date().getTime());
        final StringBuilder boxUrlSuffix = new StringBuilder("/tiff-image?maxTileSpecsToRender=999999&v=").append(versionTimestamp);
        if (parameters.channel != null) {
            boxUrlSuffix.append("&channels=").append(parameters.channel);
        }
        boxUrlSuffix.append("&name=z");

        final Function<HierarchicalStack, Integer> createStacksFunction =
                new HierarchicalStackCreationFunction(parameters.renderWeb.baseDataUrl,
                                                      parameters.renderWeb.owner,
                                                      tierZeroProject,
                                                      montageStackMetaData.getCurrentVersion(),
                                                      versionNotes,
                                                      0,
                                                      existingTierProjectStackIds,
                                                      zValues,
                                                      parameters.channel,
                                                      parameters.minIntensity,
                                                      parameters.maxIntensity,
                                                      parameters.getBoxBaseDataUrl(),
                                                      boxUrlSuffix.toString());

        final long total;
        try {
            total = createStacksFunction.call(tierZeroStack);
        } catch (final Exception e) {
            throw new IOException("failed to create tier zero stack", e);
        }

        LOG.info("createStacksForTier: exit, created {} tile specs in for tier zero in {} seconds",
                 total, timer.getElapsedSeconds());
    }

    private RenderableCanvasIdPairs getRenderablePairsForStack() {

        final List<OrderedCanvasIdPair> neighborPairs = tierZeroStack.getNeighborPairs(zValues,
                                                                                       parameters.zNeighborDistance);

        final String renderUrlTemplate =
                String.format("{baseDataUrl}/owner/%s/project/%s/stack/%s/z/{groupId}/box/{id}/render-parameters",
                              montageTilesStackId.getOwner(),
                              montageTilesStackId.getProject(),
                              montageTilesStackId.getStack());

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

        generateTierMatchesByStack(featureRenderParameters, emptyClipParameters);

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

        final String matchCollectionName = tierZeroStack.getMatchCollectionId().getName();
        // NOTE: will set count to null if match collection does not exist
        tierZeroStack.setSavedMatchPairCount(existingMatchCollectionPairCounts.get(matchCollectionName));
    }

    private void deleteExistingMatchDataForTier(final RenderDataClient driverMatchClient,
                                                final Map<String, Long> existingMatchCollectionPairCounts)
            throws IOException {

        final String matchCollectionName = tierZeroStack.getMatchCollectionId().getName();
        if (existingMatchCollectionPairCounts.containsKey(matchCollectionName)) {
            driverMatchClient.deleteMatchCollection(matchCollectionName);
        }

        if (! tierZeroStack.requiresMatchDerivation()) {
            tierZeroStack.setSavedMatchPairCount(null);
            persistHierarchicalData(tierZeroStack);
        }
    }

    private void persistHierarchicalData(final HierarchicalStack tierStack)
            throws IOException {
        driverTierRender.setHierarchicalData(tierStack.getSplitStackId().getStack(), tierStack);
    }

    private void generateTierMatchesByStack(final FeatureRenderParameters featureRenderParameters,
                                            final FeatureRenderClipParameters emptyClipParameters)
            throws IOException, URISyntaxException {

        LOG.info("generateTierMatchesByStack: entry");

            if (tierZeroStack.requiresMatchDerivation()) {

                final MatchCollectionId matchCollectionId = tierZeroStack.getMatchCollectionId();

                LOG.info("generateTierMatchesByStack: generating {}", matchCollectionId.getName());

                final MatchStorageFunction matchStorageFunction =
                        new MatchStorageFunction(parameters.renderWeb.baseDataUrl,
                                                 matchCollectionId.getOwner(),
                                                 matchCollectionId.getName());

                // TODO: do match parameters need to be tuned per tier?

                final long savedMatchPairCount =
                        SIFTPointMatchClient.generateMatchesForPairs(sparkContext,
                                                                     getRenderablePairsForStack(),
                                                                     parameters.renderWeb.baseDataUrl,
                                                                     featureRenderParameters,
                                                                     emptyClipParameters,
                                                                     parameters.featureExtraction,
                                                                     getFeatureStorageParameters(),
                                                                     parameters.matchDerivation,
                                                                     matchStorageFunction);

                tierZeroStack.setSavedMatchPairCount(savedMatchPairCount);
                persistHierarchicalData(tierZeroStack);

            }
        LOG.info("generateTierMatchesByStack: exit");
    }

    private void alignTier()
            throws IOException {

        LOG.info("alignTier: entry");

        final List<HierarchicalStack> stacksToAlign = new ArrayList<>();

        if (! parameters.keepExisting(PipelineStep.ALIGN) || tierZeroStack.requiresAlignment()) {
            stacksToAlign.add(tierZeroStack);
            tierZeroStack.setAlignmentQuality(null);
        }

        if (stacksToAlign.size() == 1) {

            // broadcast EM_aligner tool to ensure that solver is run serially on each node
            final EMAlignerTool solver = new EMAlignerTool(new File(parameters.solverScript),
                                                           new File(parameters.solverParametersTemplate));
            final Broadcast<EMAlignerTool> broadcastEMAlignerTool = sparkContext.broadcast(solver);

            final HierarchicalTierSolveFunction solveStacksFunction =
                    new HierarchicalTierSolveFunction(parameters.renderWeb.baseDataUrl,
                                                      parameters.zNeighborDistance,
                                                      broadcastEMAlignerTool);

            // remove any pre-existing alignment results ...
            driverTierRender.deleteStack(tierZeroStack.getAlignedStackId().getStack(), null);

            final JavaRDD<HierarchicalStack> rddTierStacksToAlign = sparkContext.parallelize(stacksToAlign);

            final JavaRDD<HierarchicalStack> rddTierStacksAfterAlignment =
                    rddTierStacksToAlign.map(solveStacksFunction);

            final List<HierarchicalStack> tierStacksAfterAlignment = rddTierStacksAfterAlignment.collect();

            LOG.info("alignTier: processing results");

            final Double alignmentQuality = tierStacksAfterAlignment.get(0).getAlignmentQuality();

            if ((alignmentQuality == null) || (alignmentQuality < 0.0)) {
                throw new IOException("alignment of " + tierZeroStack.getSplitStackId() +
                                      " failed (alignment quality is " + alignmentQuality + ")");
            }

            tierZeroStack.setAlignmentQuality(alignmentQuality);
            persistHierarchicalData(tierZeroStack);

            LOG.info("alignTier: {} has alignment quality {}",
                     tierZeroStack.getAlignedStackId(), tierZeroStack.getAlignmentQuality());

        } else {
            LOG.info("alignTier: all aligned stacks have already been generated");
        }

        LOG.info("alignTier: exit");
    }

    private void createRoughStack()
            throws IOException {

        LOG.info("createRoughStack: entry");

        final ProcessTimer timer = new ProcessTimer();

        final Set<StackId> existingMontageProjectStackIds = new HashSet<>(driverMontageRender.getProjectStacks());

        final StackId roughStackId = new StackId(montageTilesStackId.getOwner(),
                                                 montageTilesStackId.getProject(),
                                                 parameters.roughStack);

        boolean generateRoughStack = true;
        if (existingMontageProjectStackIds.contains(roughStackId) &&
            parameters.keepExisting(PipelineStep.ROUGH)) {
            generateRoughStack = false;
        }

        if (generateRoughStack) {

            // remove any existing rough stack results
            driverMontageRender.deleteStack(roughStackId.getStack(), null);

            final StackMetaData roughTilesStackMetaData =
                    driverMontageRender.getStackMetaData(montageTilesStackId.getStack());

            driverMontageRender.setupDerivedStack(roughTilesStackMetaData, roughStackId.getStack());

            final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);
            final HierarchicalRoughStackFunction roughStackFunction
                    = new HierarchicalRoughStackFunction(parameters.renderWeb.baseDataUrl,
                                                         tierZeroStack,
                                                         roughStackId);

            final JavaRDD<Integer> rddTileCounts = rddZValues.map(roughStackFunction);

            final List<Integer> tileCountList = rddTileCounts.collect();

            LOG.info("createRoughStack: counting results");

            long total = 0;
            for (final Integer tileCount : tileCountList) {
                total += tileCount;
            }

            LOG.info("createRoughStack: added {} tile specs to {}", total, roughStackId);

            driverMontageRender.setStackState(roughStackId.getStack(), StackMetaData.StackState.COMPLETE);
        }

        LOG.info("createRoughStack: exit, processing took {} seconds", timer.getElapsedSeconds());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RoughAlignmentClient.class);
}
