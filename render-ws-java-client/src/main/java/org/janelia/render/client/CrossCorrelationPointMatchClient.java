package org.janelia.render.client;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.CanvasCorrelationMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.CrossCorrelationParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

/**
 * Java client for generating and storing cross correlation point matches
 * for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class CrossCorrelationPointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

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

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

        @Parameter(
                names = "--failedPairsDir",
                description = "Write failed pairs (ones that did not have matches) to a JSON file in this directory",
                arity = 1)
        public String failedPairsDir;

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

                final CrossCorrelationPointMatchClient client = new CrossCorrelationPointMatchClient(parameters);
                for (final String pairJsonFileName : parameters.pairJson) {
                    client.generateMatchesForPairFile(pairJsonFileName);
                }
                client.logStats();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;
    private final RenderDataClient matchStorageClient;
    private final MatchPairCounts pairCounts;

    CrossCorrelationPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        this.matchStorageClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                       parameters.matchClient.owner,
                                                       parameters.matchClient.collection);
        this.pairCounts = new MatchPairCounts();

        // make sure the failed pairs directory exists before we get started
        if (parameters.failedPairsDir != null) {
            FileUtil.ensureWritableDirectory(new File(parameters.failedPairsDir));
        }
    }

    private void generateMatchesForPairFile(final String pairJsonFileName)
            throws IOException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        final List<CanvasMatches> nonEmptyMatchesList =
                generateMatchesForPairs(renderableCanvasIdPairs,
                                        parameters.matchClient.baseDataUrl,
                                        parameters.featureRender,
                                        parameters.featureRenderClip,
                                        parameters.correlation,
                                        parameters.matchDerivation);

        // if failed pairs directory is defined, write any failed pairs to a JSON file
        if ((parameters.failedPairsDir != null) &&
            (nonEmptyMatchesList.size() < renderableCanvasIdPairs.size())) {

            final Set<OrderedCanvasIdPair> nonEmptyMatchesSet = new HashSet<>(nonEmptyMatchesList.size());
            for (final CanvasMatches canvasMatches : nonEmptyMatchesList) {
                nonEmptyMatchesSet.add(new OrderedCanvasIdPair(new CanvasId(canvasMatches.getpGroupId(),
                                                                            canvasMatches.getpId()),
                                                               new CanvasId(canvasMatches.getqGroupId(),
                                                                            canvasMatches.getqId()),
                                                               null));
            }

            final List<OrderedCanvasIdPair>  failedPairsList = new ArrayList<>(renderableCanvasIdPairs.size());

            for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
                final CanvasId p = pair.getP();
                final CanvasId q = pair.getQ();
                final OrderedCanvasIdPair pairWithoutPosition = new OrderedCanvasIdPair(new CanvasId(p.getGroupId(),
                                                                                                     p.getId()),
                                                                                        new CanvasId(q.getGroupId(),
                                                                                                     q.getId()),
                                                                                        null);
                if (! nonEmptyMatchesSet.contains(pairWithoutPosition)) {
                    failedPairsList.add(pair);
                }
            }

            final File sourceJsonFile = new File(pairJsonFileName);
            final File failedPairsFile = new File(parameters.failedPairsDir, sourceJsonFile.getName());
            final RenderableCanvasIdPairs failedPairs =
                    new RenderableCanvasIdPairs(renderableCanvasIdPairs.getRenderParametersUrlTemplate(),
                                                failedPairsList);

            FileUtil.saveJsonFile(failedPairsFile.getAbsolutePath(), failedPairs);
        }
    }

    List<CanvasMatches> generateMatchesForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                                final String baseDataUrl,
                                                final FeatureRenderParameters featureRenderParameters,
                                                final FeatureRenderClipParameters featureRenderClipParameters,
                                                final CrossCorrelationParameters ccParameters,
                                                final MatchDerivationParameters matchDerivationParameters)
            throws IOException {

        final CanvasRenderParametersUrlTemplate urlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters,
                        featureRenderClipParameters);

        final long maximumNumberOfCachedSourcePixels = parameters.maxFeatureSourceCacheGb * 1_000_000_000;
        final ImageProcessorCache sourceImageProcessorCache =
                new ImageProcessorCache(maximumNumberOfCachedSourcePixels,
                                        true,
                                        false);

        final CanvasCorrelationMatcher matcher = new CanvasCorrelationMatcher(ccParameters,
                                                                              matchDerivationParameters,
                                                                              featureRenderParameters.renderScale);

        final List<CanvasMatches> matchList = new ArrayList<>();

        int pairCount = 0;
        CanvasIdWithRenderContext p;
        CanvasIdWithRenderContext q;
        CanvasMatchResult matchResult;
        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {

            // TODO: canvasIdWithRenderContext could be cached somewhere, thinking this is overkill for now
            p = CanvasIdWithRenderContext.build(pair.getP(), urlTemplateForRun);
            q = CanvasIdWithRenderContext.build(pair.getQ(), urlTemplateForRun);
            pairCount++;

            LOG.info("generateMatchesForPairs: derive matches between {} and {}", p, q);

            final ImageProcessorWithMasks renderedPCanvas = renderCanvas(p, sourceImageProcessorCache);
            final ImageProcessorWithMasks renderedQCanvas = renderCanvas(q, sourceImageProcessorCache);

            matchResult = matcher.deriveMatchResult(renderedPCanvas, renderedQCanvas);

            matchResult.addInlierMatchesToList(p.getGroupId(),
                                               p.getId(),
                                               q.getGroupId(),
                                               q.getId(),
                                               featureRenderParameters.renderScale,
                                               p.getClipOffsets(),
                                               q.getClipOffsets(),
                                               matchList);
        }

        LOG.info("generateMatchesForPairs: derived matches for {} out of {} pairs", matchList.size(), pairCount);
        LOG.info("generateMatchesForPairs: source cache stats are {}", sourceImageProcessorCache.getStats());

        final List<CanvasMatches> nonEmptyMatchesList = storeMatches(matchList);
        this.pairCounts.totalSaved += nonEmptyMatchesList.size();
        this.pairCounts.totalProcessed += pairCount;

        return nonEmptyMatchesList;
    }

    public static ImageProcessorWithMasks renderCanvas(final CanvasIdWithRenderContext canvasIdWithRenderContext,
                                                       final ImageProcessorCache imageProcessorCache) {
        final RenderParameters renderParameters = canvasIdWithRenderContext.loadRenderParameters();
        return Renderer.renderImageProcessorWithMasks(renderParameters,
                                                      imageProcessorCache);
    }

    private List<CanvasMatches> storeMatches(final List<CanvasMatches> allMatchesList)
            throws IOException {

        final List<CanvasMatches> nonEmptyMatchesList =
                allMatchesList.stream().filter(m -> m.size() > 0).collect(Collectors.toList());

        matchStorageClient.saveMatches(nonEmptyMatchesList);

        return nonEmptyMatchesList;
    }

    private void logStats() {
        final int percentSaved = (int) ((pairCounts.totalSaved / (double) pairCounts.totalProcessed) * 100);
        LOG.info("logStats: saved matches for {} out of {} pairs ({}%)",
                 pairCounts.totalSaved,
                 pairCounts.totalProcessed,
                 percentSaved);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CrossCorrelationPointMatchClient.class);

    private static class MatchPairCounts {
        private long totalProcessed = 0;
        private long totalSaved = 0;
    }
}
