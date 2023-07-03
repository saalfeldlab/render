package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.cache.CachedCanvasFeatures;
import org.janelia.alignment.match.cache.CachedCanvasPeaks;
import org.janelia.alignment.match.cache.CanvasDataCache;
import org.janelia.alignment.match.cache.MultiStageCanvasDataLoader;
import org.janelia.alignment.match.parameters.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.match.stage.MultiStageMatcher;
import org.janelia.alignment.match.stage.StageMatcher;
import org.janelia.alignment.match.stage.StageMatchingResources;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating and storing SIFT point matches for a specified set of canvas (e.g. tile) pairs.
 *
 * @author Eric Trautman
 */
public class MultiStagePointMatchClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @ParametersDelegate
        FeatureStorageParameters featureStorage = new FeatureStorageParameters();

        @Parameter(
                names = { "--gdMaxPeakCacheGb" },
                description = "Maximum number of gigabytes of peaks to cache")
        public Integer maxPeakCacheGb = 2;

        @Parameter(
                names = "--cacheFullScaleSourcePixels",
                description = "Indicates that full scale source images should also be cached " +
                              "for dynamically down-sampled images.  This is useful for stacks without " +
                              "down-sampled mipmaps since it avoids reloading the full scale images when " +
                              "different scales of the same image are rendered.  " +
                              "This should not be used for stacks with mipmaps since that would waste cache space.",
                arity = 0)
        public boolean cacheFullScaleSourcePixels = false;

        @Parameter(
                names = "--failedPairsDir",
                description = "Write failed pairs (ones that did not have matches) to a JSON file in this directory",
                arity = 1)
        public String failedPairsDir;

        @Parameter(
                names = "--stageJson",
                description = "JSON file where stage match parameters are defined",
                required = true)
        public String stageJson;

        @Parameter(
                names = "--pairJson",
                description = "JSON file where tile pairs are stored (.json, .gz, or .zip)",
                required = true,
                order = 5)
        public List<String> pairJson;

        private List<MatchStageParameters> stageParametersList;
        private transient ImageProcessorCache sourceImageProcessorCache;

        public Parameters() {
        }

        /**
         * Creates a minimal parameter set with pre-populated stage parameters for "library-like" 
         * usages of the client's generateMatchesForPairs and storeMatches methods.
         */
        public Parameters(final FeatureStorageParameters featureStorage,
                          final Integer maxPeakCacheGb,
                          final List<MatchStageParameters> stageParametersList) {
            this(null, featureStorage, maxPeakCacheGb, false,
                 null, null, null);
            this.stageParametersList = stageParametersList;
        }

        public Parameters(final MatchWebServiceParameters matchClient,
                          final FeatureStorageParameters featureStorage,
                          final Integer maxPeakCacheGb,
                          final boolean cacheFullScaleSourcePixels,
                          final String failedPairsDir,
                          final String stageJson,
                          final List<String> pairJson) {
            this.matchClient = matchClient;
            this.featureStorage = featureStorage;
            this.maxPeakCacheGb = maxPeakCacheGb;
            this.cacheFullScaleSourcePixels = cacheFullScaleSourcePixels;
            this.failedPairsDir = failedPairsDir;
            this.stageJson = stageJson;
            this.pairJson = pairJson;
        }

        public List<MatchStageParameters> getStageParametersList()
                throws IOException {
            if (stageParametersList == null) {
                this.buildStageParametersList();
            }
            return stageParametersList;
        }

        private synchronized void buildStageParametersList()
                throws IOException {
            if (stageParametersList == null) {
                final File stageParametersFile = new File(stageJson);
                if (! stageParametersFile.exists()) {
                    throw new IllegalArgumentException(
                            "The --stageJson file " + stageParametersFile.getAbsolutePath() + " does not exist.");
                }
                if (! stageParametersFile.canRead()) {
                    throw new IllegalArgumentException(
                            "The --stageJson file " + stageParametersFile.getAbsolutePath() + " cannot be read.");
                }

                stageParametersList = MatchStageParameters.fromJsonArrayFile(stageJson);

                if ((stageParametersList.size() > 1) && (featureStorage.rootFeatureDirectory != null)) {
                    // CanvasFeatureList writeToStorage and readToStorage methods only support one storage location
                    // for each CanvasId, so different renderings of the same canvas (for different match stages)
                    // are not currently supported.
                    throw new IllegalArgumentException(
                            "Stored features are not supported for runs with multiple stages.  " +
                            "Remove the --rootFeatureDirectory parameter or choose a --stageJson list with only one stage.");
                }

                for (final MatchStageParameters stageParameters : stageParametersList) {
                    stageParameters.validateAndSetDefaults();
                    LOG.info("buildStageParametersList: loaded stage parameters with slug {}", stageParameters.toSlug());
                }
            }
        }

        public ImageProcessorCache getSourceImageProcessorCache() {
            if (sourceImageProcessorCache == null) {
                buildSourceImageProcessorCache();
            }
            return sourceImageProcessorCache;
        }

        private synchronized void buildSourceImageProcessorCache() {
            if (sourceImageProcessorCache == null) {
                final long maximumNumberOfCachedSourcePixels = featureStorage.maxFeatureSourceCacheGb * 1_000_000_000L;
                sourceImageProcessorCache = new ImageProcessorCache(maximumNumberOfCachedSourcePixels,
                                                                    true,
                                                                    cacheFullScaleSourcePixels);
                LOG.info("buildSourceImageProcessorCache: created {}", sourceImageProcessorCache);
            }
        }
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MultiStagePointMatchClient client = new MultiStagePointMatchClient(parameters);
                for (final String pairJsonFileName : parameters.pairJson) {
                    client.generateMatchesForPairFile(pairJsonFileName);
                }

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    public MultiStagePointMatchClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
        // make sure the failed pairs directory exists before we get started
        if (parameters.failedPairsDir != null) {
            FileUtil.ensureWritableDirectory(new File(parameters.failedPairsDir));
        }
    }

    private void generateMatchesForPairFile(final String pairJsonFileName)
            throws IOException {

        LOG.info("generateMatchesForPairFile: pairJsonFileName is {}", pairJsonFileName);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = RenderableCanvasIdPairs.load(pairJsonFileName);

        final List<CanvasMatches> matchList = generateMatchesForPairs(renderableCanvasIdPairs,
                                                                      parameters.matchClient.baseDataUrl);
        final List<CanvasMatches> nonEmptyMatchesList = storeMatches(matchList,
                                                                     parameters.matchClient.getDataClient());

        if ((parameters.failedPairsDir != null) &&
            (nonEmptyMatchesList.size() < renderableCanvasIdPairs.size())) {
            SIFTPointMatchClient.writeFailedPairs(parameters.failedPairsDir,
                                                  pairJsonFileName,
                                                  renderableCanvasIdPairs,
                                                  nonEmptyMatchesList);
        }
    }

    public List<CanvasMatches> generateMatchesForPairs(final RenderableCanvasIdPairs renderableCanvasIdPairs,
                                                       final String baseDataUrl)
            throws IOException {

        final String urlTemplateString = renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl);
        final ImageProcessorCache sourceImageProcessorCache = parameters.getSourceImageProcessorCache();
        final List<MatchStageParameters> stageParametersList = parameters.getStageParametersList();

        final List<StageMatchingResources> stageResourcesList =
                StageMatchingResources.buildList(urlTemplateString,
                                                 parameters.featureStorage,
                                                 sourceImageProcessorCache,
                                                 stageParametersList);

        final MultiStageCanvasDataLoader multiStageFeatureLoader =
                new MultiStageCanvasDataLoader(CachedCanvasFeatures.class);
        final MultiStageCanvasDataLoader multiStagePeakLoader =
                new MultiStageCanvasDataLoader(CachedCanvasPeaks.class);
        for (final StageMatchingResources stageResources : stageResourcesList) {
            multiStageFeatureLoader.addLoader(stageResources.featureLoaderName, stageResources.getFeatureLoader());
            if (stageResources.hasGeometricDescriptorData()) {
                multiStagePeakLoader.addLoader(stageResources.peakLoaderName, stageResources.getPeakLoader());
            }
        }

        final long featureCacheMaxKilobytes = parameters.featureStorage.maxFeatureCacheGb * 1_000_000;
        final CanvasDataCache featureDataCache = CanvasDataCache.getSharedCache(featureCacheMaxKilobytes,
                                                                                multiStageFeatureLoader);

        final CanvasDataCache peakDataCache;
        if (multiStagePeakLoader.getNumberOfStages() > 0) {
            final long peakCacheMaxKilobytes = parameters.maxPeakCacheGb * 1000000;
            peakDataCache = CanvasDataCache.getSharedCache(peakCacheMaxKilobytes,
                                                           multiStagePeakLoader);
        }  else {
            peakDataCache = null;
        }

        final List<StageMatcher> stageMatcherList = stageResourcesList.stream()
                .map(sr -> new StageMatcher(sr, featureDataCache, peakDataCache, false))
                .collect(Collectors.toList());

        final MultiStageMatcher multiStageMatcher = new MultiStageMatcher(stageMatcherList);

        final List<CanvasMatches> matchList = new ArrayList<>();

        for (final OrderedCanvasIdPair pair : renderableCanvasIdPairs.getNeighborPairs()) {
            final MultiStageMatcher.PairResult pairResult = multiStageMatcher.generateMatchesForPair(pair);
            matchList.addAll(pairResult.getCanvasMatchesList());
        }

        final int pairCount = renderableCanvasIdPairs.size();
        SIFTPointMatchClient.logMatchStats(featureDataCache,
                                           sourceImageProcessorCache,
                                           peakDataCache,
                                           matchList,
                                           pairCount);
        multiStageMatcher.logPairCountStats();

        return matchList;
    }

    public static List<CanvasMatches> storeMatches(final List<CanvasMatches> allMatchesList,
                                                   final RenderDataClient matchDataClient)
            throws IOException {

        final List<CanvasMatches> nonEmptyMatchesList =
                allMatchesList.stream().filter(m -> m.size() > 0).collect(Collectors.toList());

        matchDataClient.saveMatches(nonEmptyMatchesList);

        return nonEmptyMatchesList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiStagePointMatchClient.class);
}
