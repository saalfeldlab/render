package org.janelia.alignment.match.stage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasPeakExtractor;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.cache.CachedCanvasFeatures;
import org.janelia.alignment.match.cache.CachedCanvasPeaks;
import org.janelia.alignment.match.cache.CanvasFeatureListLoader;
import org.janelia.alignment.match.cache.CanvasFeatureProvider;
import org.janelia.alignment.match.cache.CanvasPeakListLoader;
import org.janelia.alignment.match.cache.CanvasPeakProvider;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.FeatureStorageParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.match.MatchFilter.FilterType.CONSENSUS_SETS;

/**
 * Set of resources (URL templates, loaders, extractors, and matchers)
 * needed to run one stage of a multi-stage matching process.
 *
 * @author Eric Trautman
 */
public class StageMatchingResources
        implements Serializable, CanvasFeatureProvider, CanvasPeakProvider {

    private final MatchStageParameters stageParameters;
    private final CanvasRenderParametersUrlTemplate siftUrlTemplateForStage;
    private final boolean siftUrlTemplateMatchesPriorStageTemplate;
    private final CanvasFeatureExtractor featureExtractor;
    private final CanvasFeatureListLoader featureLoader;
    private final CanvasFeatureMatcher featureMatcher;
    private final CanvasRenderParametersUrlTemplate gdUrlTemplateForStage;
    private final boolean gdUrlTemplateMatchesPriorStageTemplate;
    private final CanvasPeakExtractor peakExtractor;
    private final CanvasPeakListLoader peakLoader;

    public String featureLoaderName;
    public String peakLoaderName;

    public StageMatchingResources(final MatchStageParameters stageParameters,
                                  final String urlTemplateString,
                                  final FeatureStorageParameters featureStorageParameters,
                                  final ImageProcessorCache sourceImageProcessorCache,
                                  final StageMatchingResources priorStageResources) {

        this.stageParameters = stageParameters;

        // determine unique set of extractors (e.g. stage_1, stage_2_3, stage_4, ...)
        // map render context to name (e.g. stage_1, stage_2, ...)
        // add render context name to key object (CanvasIdWithRenderContext)
        // loader maps context name to extractor instances

        final FeatureRenderParameters featureRenderParameters =
                stageParameters.getFeatureRender();
        final FeatureRenderClipParameters featureRenderClipParameters =
                stageParameters.getFeatureRenderClip();
        final FeatureExtractionParameters featureExtractionParameters =
                stageParameters.getFeatureExtraction();
        final MatchDerivationParameters matchDerivationParameters =
                stageParameters.getFeatureMatchDerivation();
        final GeometricDescriptorAndMatchFilterParameters gdam =
                stageParameters.getGeometricDescriptorAndMatch();

        this.siftUrlTemplateForStage =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(urlTemplateString,
                                                                    featureRenderParameters,
                                                                    featureRenderClipParameters);
        this.siftUrlTemplateMatchesPriorStageTemplate =
                (priorStageResources != null) &&
                this.siftUrlTemplateForStage.matchesExceptForScale(priorStageResources.siftUrlTemplateForStage);

        this.featureExtractor = CanvasFeatureExtractor.build(featureExtractionParameters);
        this.featureLoader =
                new CanvasFeatureListLoader(
                        this.featureExtractor,
                        featureStorageParameters.getRootFeatureDirectory(),
                        featureStorageParameters.requireStoredFeatures);

        this.featureLoader.enableSourceDataCaching(sourceImageProcessorCache);

        this.featureMatcher = new CanvasFeatureMatcher(matchDerivationParameters);

        if ((gdam != null) && gdam.isGeometricDescriptorMatchingEnabled()) {

            if (CONSENSUS_SETS.equals(matchDerivationParameters.matchFilter)) {
                throw new IllegalArgumentException(
                        "Geometric Descriptor matching is not supported when SIFT matches are grouped into CONSENSUS_SETS");
            }

            this.gdUrlTemplateForStage =
                    CanvasRenderParametersUrlTemplate.getTemplateForRun(
                            urlTemplateString,
                            featureRenderParameters.copy(gdam.renderScale,
                                                         gdam.renderWithFilter,
                                                         gdam.renderFilterListName),
                            featureRenderClipParameters);

            this.gdUrlTemplateMatchesPriorStageTemplate =
                    (priorStageResources != null) &&
                    (priorStageResources.gdUrlTemplateForStage != null) &&
                    this.gdUrlTemplateForStage.matchesExceptForScale(priorStageResources.gdUrlTemplateForStage);

            this.peakExtractor = new CanvasPeakExtractor(gdam.geometricDescriptorParameters);
            this.peakExtractor.setImageProcessorCache(sourceImageProcessorCache);

            this.peakLoader = new CanvasPeakListLoader(this.peakExtractor);

        } else {

            this.gdUrlTemplateForStage = null;
            this.gdUrlTemplateMatchesPriorStageTemplate = false;
            this.peakExtractor = null;
            this.peakLoader = null;

        }

    }

    public String getStageName() {
        return stageParameters.getStageName();
    }

    public FeatureRenderParameters getFeatureRender() {
        return stageParameters.getFeatureRender();
    }

    public MatchDerivationParameters getFeatureMatchDerivation() {
        return stageParameters.getFeatureMatchDerivation();
    }

    public GeometricDescriptorAndMatchFilterParameters getGeometricDescriptorAndMatch() {
        return stageParameters.getGeometricDescriptorAndMatch();
    }

    public boolean exceedsMaxNeighborDistance(final Double absoluteDeltaZ) {
        return stageParameters.exceedsMaxNeighborDistance(absoluteDeltaZ);
    }

    public CanvasFeatureListLoader getFeatureLoader() {
        return featureLoader;
    }

    public CanvasFeatureExtractor getFeatureExtractor() {
        return featureExtractor;
    }

    public CanvasFeatureMatcher getFeatureMatcher() {
        return featureMatcher;
    }

    public CanvasPeakListLoader getPeakLoader() {
        return peakLoader;
    }

    public CanvasPeakExtractor getPeakExtractor() {
        return peakExtractor;
    }

    public boolean hasGeometricDescriptorData() {
        return peakExtractor != null;
    }

    public boolean isSiftUrlTemplateMatchesPriorStageTemplate() {
        return siftUrlTemplateMatchesPriorStageTemplate;
    }

    public CanvasIdWithRenderContext getFeatureContextCanvasId(final CanvasId canvasId,
                                                               final CanvasIdWithRenderContext priorStageCanvasId) {
        final CanvasIdWithRenderContext matchingPriorStageCanvasId =
                siftUrlTemplateMatchesPriorStageTemplate ? priorStageCanvasId : null;
        return CanvasIdWithRenderContext.build(canvasId,
                                               siftUrlTemplateForStage,
                                               featureLoaderName,
                                               matchingPriorStageCanvasId);
    }

    public CanvasIdWithRenderContext getPeakContextCanvasId(final CanvasId canvasId,
                                                            final CanvasIdWithRenderContext priorStageCanvasId) {
        CanvasIdWithRenderContext peakContextCanvasId = null;

        // only build peak canvasId if geometric descriptor matching is enabled
        if (peakLoader != null) {
            final CanvasIdWithRenderContext matchingPriorStageCanvasId =
                    gdUrlTemplateMatchesPriorStageTemplate ? priorStageCanvasId : null;
            peakContextCanvasId = CanvasIdWithRenderContext.build(canvasId,
                                                                  gdUrlTemplateForStage,
                                                                  peakLoaderName,
                                                                  matchingPriorStageCanvasId);
        }

        return peakContextCanvasId;
    }

    @Override
    public CachedCanvasFeatures getCanvasFeatures(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException {
        return featureLoader.load(canvasIdWithRenderContext);
    }

    @Override
    public CachedCanvasPeaks getCanvasPeaks(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException {
        return peakLoader.load(canvasIdWithRenderContext);
    }

    public static List<StageMatchingResources> buildList(final String urlTemplateString,
                                                         final FeatureStorageParameters featureStorageParameters,
                                                         final ImageProcessorCache sourceImageProcessorCache,
                                                         final List<MatchStageParameters> stageParametersList) {

        LOG.info("buildList: entry, setting up resources for {} stages", stageParametersList.size());

        StageMatchingResources stageResources = null;
        final List<StageMatchingResources> stageResourcesList = new ArrayList<>();
        for (final MatchStageParameters stageParameters : stageParametersList) {
            stageResources = new StageMatchingResources(stageParameters,
                                                        urlTemplateString,
                                                        featureStorageParameters,
                                                        sourceImageProcessorCache,
                                                        stageResources);
            stageResourcesList.add(stageResources);
        }

        final Map<CanvasFeatureExtractor, List<Integer>> featureExtractorToIndexMap = new HashMap<>();
        final Map<CanvasPeakExtractor, List<Integer>> peakExtractorToIndexMap = new HashMap<>();
        for (int i = 0; i < stageResourcesList.size(); i++) {
            stageResources = stageResourcesList.get(i);
            final List<Integer> indexesForFeatureExtractor =
                    featureExtractorToIndexMap.computeIfAbsent(stageResources.getFeatureExtractor(),
                                                               fe -> new ArrayList<>());
            indexesForFeatureExtractor.add(i);
            final List<Integer> indexesForPeakExtractor =
                    peakExtractorToIndexMap.computeIfAbsent(stageResources.getPeakExtractor(),
                                                            pe -> new ArrayList<>());
            indexesForPeakExtractor.add(i);
        }

        featureExtractorToIndexMap.values()
                .forEach(indexList -> indexList
                        .forEach(i -> stageResourcesList.get(i).featureLoaderName = getLoaderName("feature",
                                                                                                  indexList)));

        peakExtractorToIndexMap.values()
                .forEach(indexList -> indexList
                        .forEach(i -> stageResourcesList.get(i).peakLoaderName = getLoaderName("peak",
                                                                                               indexList)));

        LOG.info("buildList: feature loader names are {}",
                 stageResourcesList.stream().map(sr -> sr.featureLoaderName).collect(Collectors.toList()));

        LOG.info("buildList: peak loader names are {}",
                 stageResourcesList.stream().map(sr -> sr.peakLoaderName).collect(Collectors.toList()));

        return stageResourcesList;
    }

    private static String getLoaderName(final String context,
                                        final List<Integer> indexList) {
        final StringBuilder sb = new StringBuilder(context);
        for (final Integer i : indexList) {
            sb.append("_").append(i);
        }
        return sb.toString();
    }

    private static final Logger LOG = LoggerFactory.getLogger(StageMatchingResources.class);
}
