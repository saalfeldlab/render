package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.ImagePlus;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.UnscaleTile;
import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.transform.ExponentialRecoveryOffsetTransform;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.render.client.CrossCorrelationPointMatchClient.renderCanvas;

/**
 * Java client for generating offset transforms for one tile in each pair
 * of a specified set of tile pairs.
 */
public class ExponentialRecoveryOffsetTransformDerivationClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Name of source stack containing base tile specifications",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetProject",
                description = "Name of target project that will contain transformed tiles (default is to reuse source project)"
        )
        public String targetProject;

        public String getTargetProject() {
            if ((targetProject == null) || (targetProject.trim().length() == 0)) {
                targetProject = renderWeb.project;
            }
            return targetProject;
        }

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack that will contain transformed tiles (default is to reuse source stack)"
        )
        public String targetStack;

        public String getTargetStack() {
            if ((targetStack == null) || (targetStack.trim().length() == 0)) {
                targetStack = stack;
            }
            return targetStack;
        }

        @Parameter(
                names = "--setupTargetStack",
                description = "Setup the target stack before transforming tiles",
                arity = 0)
        public boolean setupTargetStack = false;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after transforming all tiles",
                arity = 0)
        public boolean completeTargetStack = false;

        @Parameter(
                names = "--problemIdPatternString",
                description = "Pattern to identify problem tile/canvas within each pair that needs transformation",
                required = true)
        public String problemIdPatternString;

        @ParametersDelegate
        FeatureRenderParameters featureRender = new FeatureRenderParameters();

        @ParametersDelegate
        FeatureRenderClipParameters featureRenderClip = new FeatureRenderClipParameters();

        @Parameter(
                names = { "--maxFeatureSourceCacheGb" },
                description = "Maximum number of gigabytes of source image and mask data to cache " +
                              "(note: 15000x15000 Fly EM mask is 500MB while 6250x4000 mask is 25MB)"
        )
        public Integer maxFeatureSourceCacheGb = 2;

        @ParametersDelegate
        CrossCorrelationParameters correlation = new CrossCorrelationParameters();

        @Parameter(
                names = "--fitResultsDir",
                description = "Write fit results to a JSON file in this directory for debug (omit to skip write)",
                arity = 1)
        public String fitResultsDir;

        @Parameter(
                names = "--pairJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                order = 5)
        public List<String> pairJson;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ExponentialRecoveryOffsetTransformDerivationClient
                        client = new ExponentialRecoveryOffsetTransformDerivationClient(parameters);

                if (parameters.setupTargetStack) {
                    client.setupDerivedStack();
                }

                for (final String pairJsonFileName : parameters.pairJson) {
                    client.deriveTransformsForPairFile(pairJsonFileName);
                }

                if (parameters.completeTargetStack) {
                    client.completeTargetStack();
                }

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient sourceRenderDataClient;
    private final RenderDataClient targetRenderDataClient;

    ExponentialRecoveryOffsetTransformDerivationClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        this.sourceRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                           parameters.renderWeb.owner,
                                                           parameters.renderWeb.project);
        this.targetRenderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                           parameters.renderWeb.owner,
                                                           parameters.getTargetProject());
        // make sure the results directory exists before we get started
        if (parameters.fitResultsDir != null) {
            FileUtil.ensureWritableDirectory(new File(parameters.fitResultsDir));
        }
    }

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static class FitResultWithContext {
        private final Double z;
        private final String tileId;
        private final UnscaleTile.FitResult fitResult;

        public FitResultWithContext(final CanvasId canvasId,
                                    final UnscaleTile.FitResult fitResult) {
            this.z = Double.parseDouble(canvasId.getGroupId());
            this.tileId = canvasId.getId();
            this.fitResult = fitResult;
        }

        public String toJson() {
            return FIT_RESULT_WITH_CONTEXT_JSON_HELPER.toJson(this);
        }
    }

    private void deriveTransformsForPairFile(final String pairJsonFileName)
            throws IOException {

        LOG.info("deriveTransformsForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        final Map<CanvasId, UnscaleTile.FitResult> canvasIdToResultsMap =
                deriveTransformParametersForPairs(renderableCanvasIdPairs);

        if (parameters.fitResultsDir != null) {
            saveFitResultsToJsonFile(pairJsonFileName, canvasIdToResultsMap);
        }

        insertFitResultTransformsIntoTileSpecs(canvasIdToResultsMap);
    }

    private Map<CanvasId, UnscaleTile.FitResult> deriveTransformParametersForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs) {

        final Map<CanvasId, UnscaleTile.FitResult> canvasIdToResultsMap = new HashMap<>();

        final CanvasRenderParametersUrlTemplate urlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(parameters.renderWeb.baseDataUrl),
                        parameters.featureRender,
                        parameters.featureRenderClip);

        final long maximumNumberOfCachedSourcePixels = parameters.maxFeatureSourceCacheGb * 1_000_000_000;
        final ImageProcessorCache sourceImageProcessorCache =
                new ImageProcessorCache(maximumNumberOfCachedSourcePixels,
                                        true,
                                        false);

        final Pattern problemIdPattern = Pattern.compile(parameters.problemIdPatternString);

        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {

            final boolean pIsProblem = problemIdPattern.matcher(pair.getP().getId()).matches();

            if ((! pIsProblem) &&
                (! problemIdPattern.matcher(pair.getQ().getId()).matches())) {
                throw new IllegalArgumentException(
                        "neither " + pair.getP().getId() + " nor " + pair.getQ().getId() +
                        " matches problem pattern '" + parameters.problemIdPatternString + "'");
            }

            final CanvasId problemCanvasId = pIsProblem ? pair.getP() : pair.getQ();
            final CanvasId otherCanvasId = pIsProblem ? pair.getQ() : pair.getP();

            final CanvasIdWithRenderContext problemCanvasIdWithRenderContext =
                    CanvasIdWithRenderContext.build(problemCanvasId, urlTemplateForRun);
            final CanvasIdWithRenderContext otherCanvasIdWithRenderContext =
                    CanvasIdWithRenderContext.build(otherCanvasId, urlTemplateForRun);

            LOG.info("deriveTransformParametersForPairs: derive parameters for {} using {}",
                     problemCanvasIdWithRenderContext, otherCanvasIdWithRenderContext);

            final ImageProcessorWithMasks renderedProblemCanvas = renderCanvas(problemCanvasIdWithRenderContext,
                                                                               sourceImageProcessorCache);
            final ImageProcessorWithMasks renderedOtherCanvas = renderCanvas(otherCanvasIdWithRenderContext,
                                                                             sourceImageProcessorCache);

            final ImagePlus problemImagePlus = new ImagePlus(problemCanvasIdWithRenderContext.getId(),
                                                             renderedProblemCanvas.ip);
            final ImagePlus otherImagePlus = new ImagePlus(otherCanvasIdWithRenderContext.getId(),
                                                           renderedOtherCanvas.ip);

            final UnscaleTile.FitResult fitResult = UnscaleTile.getScalingFunction(
                    problemImagePlus,
                    renderedProblemCanvas.mask,
                    otherImagePlus,
                    renderedOtherCanvas.mask,
                    false,
                    parameters.featureRender.renderScale,
                    parameters.correlation);

            canvasIdToResultsMap.put(problemCanvasId, fitResult);
        }

        LOG.info("deriveTransformParametersForPairs: derived parameters for {} pairs",
                 canvasIdToResultsMap.size());
        LOG.info("deriveTransformParametersForPairs: source cache stats are {}",
                 sourceImageProcessorCache.getStats());

        return canvasIdToResultsMap;
    }

    private void saveFitResultsToJsonFile(final String pairJsonFileName,
                                          final Map<CanvasId, UnscaleTile.FitResult> canvasIdToResultsMap)
            throws IOException {

        final List<FitResultWithContext> resultList = new ArrayList<>(canvasIdToResultsMap.size());

        canvasIdToResultsMap.keySet().forEach(
                canvasId -> resultList.add(new FitResultWithContext(canvasId, canvasIdToResultsMap.get(canvasId))));

        resultList.sort(Comparator.comparing(o -> o.z));

        final File sourceJsonFile = new File(pairJsonFileName);
        final String sourceJsonName = sourceJsonFile.getName();
        final int dotJsonLoc = sourceJsonName.indexOf(".json");
        final String resultsName;
        if (dotJsonLoc > -1) {
            resultsName = sourceJsonName.substring(0, dotJsonLoc) + ".results.json";
        } else {
            resultsName = sourceJsonName + ".results.json";
        }
        final File resultsFile = new File(parameters.fitResultsDir, resultsName);

        FileUtil.saveJsonFile(resultsFile.getAbsolutePath(), resultList);
    }

    private void insertFitResultTransformsIntoTileSpecs(final Map<CanvasId, UnscaleTile.FitResult> canvasIdToResultsMap)
            throws IOException {

        final ResolvedTileSpecCollection updatedTileSpecs = new ResolvedTileSpecCollection();

        for (final CanvasId canvasId : canvasIdToResultsMap.keySet()) {

            final TileSpec tileSpec = sourceRenderDataClient.getTile(parameters.stack, canvasId.getId());
            final ListTransformSpec sourceListTransformSpec = tileSpec.getTransforms();
            final int numberOfSourceTransforms = sourceListTransformSpec.size();

            final ListTransformSpec targetListTransformSpec = new ListTransformSpec();

            // insert fit result transform at beginning of tile spec transform list
            final ExponentialRecoveryOffsetTransform transform = canvasIdToResultsMap.get(canvasId).buildTransform();
            targetListTransformSpec.addSpec(new LeafTransformSpec(transform.getClass().getName(),
                                                                  transform.toDataString()));

            // then append existing source transforms
            for (int i = 0; i< numberOfSourceTransforms; i++) {
                final TransformSpec sourceTransformSpec = sourceListTransformSpec.getSpec(i);
                if (! sourceTransformSpec.isFullyResolved()) {
                    // TODO: consider fetching dependencies for reference transform specs
                    throw new UnsupportedOperationException(
                            "tile spec " + tileSpec.getTileId() + " has unresolved transforms");
                }
                targetListTransformSpec.addSpec(sourceListTransformSpec.getSpec(i));
            }

            tileSpec.setTransforms(targetListTransformSpec);
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            updatedTileSpecs.addTileSpecToCollection(tileSpec);
        }

        targetRenderDataClient.saveResolvedTiles(updatedTileSpecs, parameters.getTargetStack(), null);
    }

    private void setupDerivedStack()
            throws IOException {
        final StackMetaData sourceStackMetaData = sourceRenderDataClient.getStackMetaData(parameters.stack);
        targetRenderDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());
    }

    private void completeTargetStack() throws Exception {
        targetRenderDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ExponentialRecoveryOffsetTransformDerivationClient.class);

    private static final JsonUtils.Helper<FitResultWithContext> FIT_RESULT_WITH_CONTEXT_JSON_HELPER =
            new JsonUtils.Helper<>(FitResultWithContext.class);
}
