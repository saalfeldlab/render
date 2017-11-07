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
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchClipParameters;
import org.janelia.render.client.parameter.MatchDerivationParameters;
import org.janelia.render.client.parameter.MatchRenderParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
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
                description = "Last tier to generate",
                required = false)
        public Integer lastTier = 1;

        @Parameter(
                names = "--maxPixelsPerDimension",
                description = "Scale each tier such that the number of pixels in the largest dimension is this number",
                required = false)
        public Integer maxPixelsPerDimension = 2048;

        @Parameter(
                names = "--firstTier",
                description = "First tier to generate",
                required = false)
        public Integer firstTier = 1;

        @Parameter(
                names = "--zNeighborDistance",
                description = "Generate matches between layers with z values less than or equal to this distance from the current layer's z value",
                required = false)
        public Integer zNeighborDistance = 2;

        @Parameter(
                names = "--renderWithFilter",
                description = "Render tiles using a filter for intensity correction",
                required = false,
                arity = 1)
        public boolean renderWithFilter = true;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill each canvas image with noise before rendering to improve point match derivation",
                required = false,
                arity = 1)
        public boolean fillWithNoise = true;

        @Parameter(
                names = "--channel",
                description = "Name of channel to use for alignment (omit if data is not multi-channel)",
                required = false)
        public String channel;

        @Parameter(
                names = "--boxBaseDataUrl",
                description = "Base web service URL for boxes referenced in tiered split stacks (e.g. http://host[:port]/render-ws/v1).  If omitted, baseDataUrl will be used.",
                required = false)
        public String boxBaseDataUrl;

        @Parameter(
                names = "--minIntensity",
                description = "Minimum intensity for all tiered split stack canvas tiles",
                required = false)
        public Double minIntensity = 0.0;

        @Parameter(
                names = "--maxIntensity",
                description = "Maximum intensity for all tiered split stack canvas tiles",
                required = false)
        public Double maxIntensity = 255.0;

        @ParametersDelegate
        public MatchDerivationParameters match = new MatchDerivationParameters();

        @Parameter(
                names = "--solverScript",
                description = "Full path for solver",
                required = false)
        public String solverScript = "/groups/flyTEM/flyTEM/matlab_compiled/bin/run_system_solve_affine_with_constraint_SL.sh";

        @Parameter(
                names = "--solverParametersTemplate",
                description = "Full path for solver parameters json file to be used as template for all solver runs",
                required = true)
        public String solverParametersTemplate;

        @Parameter(
                names = "--keepExisting",
                description = "Pipeline stage for which all prior existing results should be kept",
                required = false)
        public PipelineStep keepExistingStep;

        public String getBoxBaseDataUrl() {
            return boxBaseDataUrl == null ? renderWeb.baseDataUrl : boxBaseDataUrl;
        }

        public boolean keepExisting(final PipelineStep step) {
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

                final HierarchicalAlignmentClient client = new HierarchicalAlignmentClient(parameters);
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
    private RenderDataClient driverTierRender;
    private final List<HierarchicalStack> tierStacks;


    public HierarchicalAlignmentClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;

        final SparkConf conf = new SparkConf().setAppName("HierarchicalAlignmentClient");
        this.sparkContext = new JavaSparkContext(conf);
        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        this.driverRoughRender = parameters.renderWeb.getDataClient();

        this.roughTilesStackId = new StackId(parameters.renderWeb.owner,
                                             parameters.renderWeb.project,
                                             parameters.stack);
        this.zValues = new ArrayList<>();

        this.tierStacks = new ArrayList<>();
    }

    public void run() throws IOException, URISyntaxException {

        this.zValues.addAll(driverRoughRender.getStackZValues(parameters.stack));

        for (int tier = parameters.firstTier; tier <= parameters.lastTier; tier++) {

            final StackId parentTilesStackId = HierarchicalStack.deriveParentTierStackId(roughTilesStackId, tier);
            final StackMetaData parentStackMetaData = driverRoughRender.getStackMetaData(parentTilesStackId.getStack());

            setupSplitStacksForTier(tier, parentStackMetaData);
            createStacksForTier(tier, parentStackMetaData);
            generateMatchesForTier();
            alignTier();
            createWarpStackForTier();

            // after the first requested tier is processed,
            // remove the keepExisting flag so that subsequent tiers are processed in their entirety
            parameters.keepExistingStep = null;

            // TODO: decide whether to continue to the next tier
        }

        sparkContext.stop();
    }

    private StackMetaData setupSplitStacksForTier(final int tier,
                                                  final StackMetaData parentStackMetaData)
            throws IOException {

        LOG.info("setupSplitStacksForTier: entry, tier={}", tier);

        final Bounds parentStackBounds = parentStackMetaData.getStats().getStackBounds();

        tierStacks.clear();
        tierStacks.addAll(
                HierarchicalStack.splitTier(roughTilesStackId,
                                            parentStackBounds,
                                            parameters.maxPixelsPerDimension,
                                            tier));

        if (tierStacks.size() == 0) {
            throw new IllegalStateException("no split stacks for tier " + tier + " of " + roughTilesStackId);
        }

        return parentStackMetaData;
    }

    private void createStacksForTier(final int tier,
                                     final StackMetaData parentStackMetaData)
            throws IOException {

        LOG.info("createStacksForTier: entry, tier={}", tier);

        final ProcessTimer timer = new ProcessTimer();

        final String tierProject = HierarchicalStack.deriveProjectForTier(roughTilesStackId, tier);
        driverTierRender = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                parameters.renderWeb.owner,
                                                tierProject);

        final String versionNotes = "tier " + tier + " stack derived from " + parentStackMetaData.getStackId();

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
        final StringBuilder boxUrlSuffix = new StringBuilder("/tiff-image?v=").append(versionTimestamp);
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
                                                      tier,
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
                 total, tierStacks.size(), tier, timer.getElapsedSeconds());
    }

    private RenderableCanvasIdPairs getRenderablePairsForStack(final HierarchicalStack tierStack) {

        final int n = zValues.size();
        final List<OrderedCanvasIdPair> neighborPairs = new ArrayList<>(n * parameters.zNeighborDistance);
        Double pz;
        Double qz;
        CanvasId p;
        CanvasId q;
        for (int i = 0; i < n; i++) {
            pz = zValues.get(i);
            p = new CanvasId(pz.toString(), tierStack.getTileIdForZ(pz));
            for (int k = i + 1; k < n && k < i + parameters.zNeighborDistance; k++) {
                qz = zValues.get(k);
                q = new CanvasId(qz.toString(), tierStack.getTileIdForZ(qz));
                neighborPairs.add(new OrderedCanvasIdPair(p, q));
            }
        }

        if (neighborPairs.size() > 0) {
            LOG.info("getRenderablePairsForStack: first neighbor pair is {}", neighborPairs.get(0));
        }

        final StackId parentTierStackId = tierStack.getParentTierStackId();
        final String renderUrlTemplate =
                String.format("{baseDataUrl}/owner/%s/project/%s/stack/%s/z/{groupId}/box/{id}/render-parameters",
                              parentTierStackId.getOwner(),
                              parentTierStackId.getProject(),
                              parentTierStackId.getStack());

        LOG.info("getRenderablePairsForStack: exit, returning {} pairs with template {}",
                 neighborPairs.size(), renderUrlTemplate);

        return new RenderableCanvasIdPairs(renderUrlTemplate, neighborPairs);
    }

    private void generateMatchesForTier()
            throws IOException, URISyntaxException {

        LOG.info("generateMatchesForTier: entry");

        final MatchWebServiceParameters matchWebServiceParameters = new MatchWebServiceParameters();
        matchWebServiceParameters.baseDataUrl = parameters.renderWeb.baseDataUrl;
        matchWebServiceParameters.owner = parameters.renderWeb.owner;
        // match collection changed for each split stack below

        final MatchRenderParameters matchRenderParameters = new MatchRenderParameters();
        matchRenderParameters.fillWithNoise = parameters.fillWithNoise;
        matchRenderParameters.renderWithFilter = parameters.renderWithFilter;
        matchRenderParameters.renderWithoutMask = false; // always include masks because we are rendering scapes
        matchRenderParameters.renderScale = 1.0; // always render full scale because canvases are already scaled down

        final MatchClipParameters emptyClipParameters = new MatchClipParameters(); // no need to clip scapes

        final RenderDataClient driverMatchClient = new RenderDataClient(matchWebServiceParameters.baseDataUrl,
                                                                        matchWebServiceParameters.owner,
                                                                        "not_applicable");

        final Map<String, Long> existingMatchCollections = new HashMap<>();
        for (final MatchCollectionMetaData metaData : driverMatchClient.getOwnerMatchCollections()) {
            existingMatchCollections.put(metaData.getCollectionId().getName(), metaData.getPairCount());
        }

        // TODO: need to generate matches for split stacks in parallel
        //       if we created one giant pool of pairs, we'd need to somehow track the collection for each pair (tuple?)
        //       (everything else is shared)

        // TODO: do match parameters need to be tuned per tier?

        for (final HierarchicalStack tierStack : tierStacks) {

            final MatchCollectionId matchCollectionId = tierStack.getMatchCollectionId();
            matchWebServiceParameters.owner = matchCollectionId.getOwner();
            matchWebServiceParameters.collection = matchCollectionId.getName();

            if (existingMatchCollections.containsKey(matchWebServiceParameters.collection)) {

                if (parameters.keepExisting(PipelineStep.MATCH)) {

                    LOG.info("generateMatchesForTier: skipping generation of {} because it already exists",
                             matchWebServiceParameters.collection);

                    tierStack.setSavedMatchPairCount(
                            existingMatchCollections.get(matchWebServiceParameters.collection));

                } else {

                    driverMatchClient.deleteMatchCollection(matchWebServiceParameters.collection);

                }
            }

            if (tierStack.requiresMatchDerivation()) {

                LOG.info("generateMatchesForTier: generating {}", matchWebServiceParameters.collection);

                final long savedMatchPairCount =
                        SIFTPointMatchClient.generateMatchesForPairs(sparkContext,
                                                                     getRenderablePairsForStack(tierStack),
                                                                     matchWebServiceParameters,
                                                                     matchRenderParameters,
                                                                     parameters.match,
                                                                     emptyClipParameters);

                tierStack.setSavedMatchPairCount(savedMatchPairCount);
                driverTierRender.setHierarchicalData(tierStack.getSplitStackId().getStack(), tierStack);

            }
        }

        LOG.info("generateMatchesForTier: exit");
    }

    private void alignTier()
            throws IOException {

        LOG.info("alignTier: entry");

        // broadcast EM_aligner tool to ensure that solver is run serially on each node
        final EMAlignerTool solver = new EMAlignerTool(new File(parameters.solverScript),
                                                       new File(parameters.solverParametersTemplate));
        final Broadcast<EMAlignerTool> broadcastEMAlignerTool = sparkContext.broadcast(solver);

        final HierarchicalTierSolveFunction solveStacksFunction =
                new HierarchicalTierSolveFunction(parameters.boxBaseDataUrl,
                                                  broadcastEMAlignerTool);

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

            // remove any pre-existing alignment results ...
            for (final HierarchicalStack splitStack : stacksToAlign) {
                driverTierRender.deleteStack(splitStack.getAlignedStackId().getStack(), null);
            }

            final JavaRDD<HierarchicalStack> rddStacksToAlign = sparkContext.parallelize(stacksToAlign);

            final JavaRDD<HierarchicalStack> rddAlignedStacks = rddStacksToAlign.map(solveStacksFunction);

            final List<HierarchicalStack> alignedStackList = rddAlignedStacks.collect();

            LOG.info("alignTier: processing results");

            for (final HierarchicalStack splitStack : alignedStackList) {
                final String splitStackName = splitStack.getSplitStackId().getStack();
                LOG.info("alignTier: stack {} has alignment quality {}",
                         splitStackName, splitStack.getAlignmentQuality());

                driverTierRender.setHierarchicalData(splitStackName, splitStack);
            }

        } else {
            LOG.info("alignTier: all aligned stacks have already been generated");
        }

        LOG.info("alignTier: exit");
    }

    private void createWarpStackForTier() {
        LOG.info("createWarpStackForTier: entry");
        // TODO: fetch align stack affines, create warp field transform, and apply to rough tiles
        LOG.info("createWarpStackForTier: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalAlignmentClient.class);
}
