package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.render.client.cache.CachedCanvasFeatures;
import org.janelia.render.client.cache.CanvasDataCache;
import org.janelia.render.client.cache.CanvasFeatureListLoader;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.FeatureRenderParameters;
import org.janelia.render.client.parameter.FeatureStorageParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating and storing SIFT point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class SIFTPointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        FeatureRenderParameters featureRender = new FeatureRenderParameters();

        @ParametersDelegate
        FeatureRenderClipParameters featureRenderClip = new FeatureRenderClipParameters();

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @ParametersDelegate
        FeatureStorageParameters featureStorage = new FeatureStorageParameters();

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

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

                final SIFTPointMatchClient client = new SIFTPointMatchClient(parameters);
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

    private long totalProcessed;
    private long totalSaved;

    private SIFTPointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        this.matchStorageClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                       parameters.matchClient.owner,
                                                       parameters.matchClient.collection);
        this.totalProcessed = 0;
        this.totalSaved = 0;
    }

    private void generateMatchesForPairFile(final String pairJsonFileName)
            throws IOException, URISyntaxException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        generateMatchesForPairs(renderableCanvasIdPairs,
                                parameters.matchClient.baseDataUrl,
                                parameters.featureRender,
                                parameters.featureRenderClip,
                                parameters.featureExtraction,
                                parameters.featureStorage,
                                parameters.matchDerivation);
    }

    private void generateMatchesForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                         final String baseDataUrl,
                                         final FeatureRenderParameters featureRenderParameters,
                                         final FeatureRenderClipParameters featureRenderClipParameters,
                                         final FeatureExtractionParameters featureExtractionParameters,
                                         final FeatureStorageParameters featureStorageParameters,
                                         final MatchDerivationParameters matchDerivationParameters)
            throws IOException, URISyntaxException {

        final CanvasRenderParametersUrlTemplate urlTemplateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        featureRenderParameters.renderFullScaleWidth,
                        featureRenderParameters.renderFullScaleHeight,
                        featureRenderParameters.renderScale,
                        featureRenderParameters.renderWithFilter,
                        featureRenderParameters.renderFilterListName,
                        featureRenderParameters.renderWithoutMask);

        urlTemplateForRun.setClipInfo(featureRenderClipParameters.clipWidth, featureRenderClipParameters.clipHeight);

        final long cacheMaxKilobytes = featureStorageParameters.maxCacheGb * 1000000;
        final CanvasFeatureListLoader featureLoader =
                new CanvasFeatureListLoader(
                        urlTemplateForRun,
                        getCanvasFeatureExtractor(featureExtractionParameters, featureRenderParameters),
                        featureStorageParameters.getRootFeatureDirectory(),
                        featureStorageParameters.requireStoredFeatures);

        final CanvasDataCache dataCache = CanvasDataCache.getSharedCache(cacheMaxKilobytes, featureLoader);
        final CanvasFeatureMatcher featureMatcher = new CanvasFeatureMatcher(matchDerivationParameters);

        final List<CanvasMatches> matchList = new ArrayList<>();

        CanvasId p;
        CanvasId q;
        CachedCanvasFeatures pFeatures;
        CachedCanvasFeatures qFeatures;
        CanvasFeatureMatchResult matchResult;
        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
            p = pair.getP();
            q = pair.getQ();

            pFeatures = dataCache.getCanvasFeatures(p);
            qFeatures = dataCache.getCanvasFeatures(q);

            LOG.info("generateMatchesForPairs: derive matches between {} and {}", p, q);

            matchResult = featureMatcher.deriveMatchResult(pFeatures.getFeatureList(),
                                                           qFeatures.getFeatureList());

            final double[] pClipOffsets = pFeatures.getClipOffsets();
            final double[] qClipOffsets = qFeatures.getClipOffsets();

            matchResult.addInlierMatchesToList(p.getGroupId(),
                                               p.getId(),
                                               q.getGroupId(),
                                               q.getId(),
                                               featureRenderParameters.renderScale,
                                               pClipOffsets,
                                               qClipOffsets,
                                               matchList);
        }

        final int pairCount = renderableCanvasIdPairs.size();

        LOG.info("generateMatchesForPairs: derived matches for {} out of {} pairs, cache stats are {}",
                 matchList.size(), pairCount, dataCache.stats());

        this.totalSaved += storeMatches(matchList);
        this.totalProcessed += pairCount;
    }

    private int storeMatches(final List<CanvasMatches> allMatchesList)
            throws IOException {

        final List<CanvasMatches> nonEmptyMatchesList =
                allMatchesList.stream().filter(m -> m.size() > 0).collect(Collectors.toList());

        matchStorageClient.saveMatches(nonEmptyMatchesList);

        return nonEmptyMatchesList.size();
    }

    private void logStats() {
        final int percentSaved = (int) ((totalSaved / (double) totalProcessed) * 100);
        LOG.info("logStats: saved matches for {} out of {} pairs ({}%)",
                 totalSaved, totalProcessed, percentSaved);
    }

    public static CanvasFeatureExtractor getCanvasFeatureExtractor(final FeatureExtractionParameters featureExtraction,
                                                                    final FeatureRenderParameters featureRender) {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = featureExtraction.fdSize;
        siftParameters.steps = featureExtraction.steps;

        return new CanvasFeatureExtractor(siftParameters,
                                          featureExtraction.minScale,
                                          featureExtraction.maxScale,
                                          featureRender.fillWithNoise);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SIFTPointMatchClient.class);
}
