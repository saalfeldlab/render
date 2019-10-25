package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasFeatureMatchResult;
import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for identifying optimal render scale and match ratio of distances values
 * for SIFT point match derivation.
 *
 * @author Eric Trautman
 */
public class PointMatchOptimizerClient {

    public static class Parameters extends CommandLineParameters {

        @Parameter(
                names = "--baseCanvasUrl",
                description = "Base URL for canvas data (e.g. http://host[:port]/render-ws/v1/owner/flyTEM/project/FAFB00/stack/v12_acquire/tile)",
                required = true)
        public String baseCanvasUrl;

        @Parameter(
                names = "--pId",
                description = "Identifier for P canvas",
                required = true)
        public String pId;

        @Parameter(
                names = "--qId",
                description = "Identifier for P canvas",
                required = true)
        public String qId;

        @Parameter(
                names = "--renderScaleStep",
                description = "Amount to adjust render scale for each iteration during optimization"
        )
        public Double renderScaleStep = 0.1;

        @Parameter(
                names = "--minFeatureCount",
                description = "Minimum number features for optimal render scale"
        )
        public Integer minFeatureCount = 3000;

        @Parameter(
                names = "--maxFeatureCount",
                description = "Maximum number features for optimal render scale"
        )
        public Integer maxFeatureCount = 6000;

        @ParametersDelegate
        FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();

        @ParametersDelegate
        MatchDerivationParameters matchDerivation = new MatchDerivationParameters();

        @Parameter(
                names = "--matchRodStep",
                description = "Amount to adjust ratio of distances for each iteration during optimization"
        )
        public Double matchRodStep = 0.05;

    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final PointMatchOptimizerClient client = new PointMatchOptimizerClient(parameters);
                client.run();

            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final String pRenderParametersUrl;
    private final String qRenderParametersUrl;

    private final Map<Double, List<Feature>> scaleToPFeatureListMap;
    private final Map<Double, List<Feature>> scaleToQFeatureListMap;

    private PointMatchOptimizerClient(final Parameters clientParameters)
            throws IllegalArgumentException {

        this.parameters = clientParameters;
        this.pRenderParametersUrl = clientParameters.baseCanvasUrl + "/" + clientParameters.pId + "/render-parameters";
        this.qRenderParametersUrl = clientParameters.baseCanvasUrl + "/" + clientParameters.qId + "/render-parameters";

        this.scaleToPFeatureListMap = new HashMap<>();
        this.scaleToQFeatureListMap = new HashMap<>();
    }

    /**
     * Extract features from distinct set of canvases.
     */
    public void run() {

        Double optimalRenderScale = null;

        double renderScale = 0.5;
        double previousRenderScale = renderScale;
        int previousMinFeatureCount = 0;
        int minFeatureCount;
        while ((optimalRenderScale == null) && (renderScale > 0) && (renderScale < 1.1)) {

            minFeatureCount = extractFeaturesForScale(renderScale);

            if (minFeatureCount < parameters.minFeatureCount) {

                if (previousMinFeatureCount >= parameters.minFeatureCount) {
                    optimalRenderScale = previousRenderScale;
                } else {
                    previousRenderScale = renderScale;
                    renderScale += parameters.renderScaleStep;
                }

            } else if (minFeatureCount > parameters.maxFeatureCount) {

                previousRenderScale = renderScale;
                renderScale -= parameters.renderScaleStep;

            } else {

                optimalRenderScale = renderScale;

            }

            previousMinFeatureCount = minFeatureCount;
        }

        if (optimalRenderScale == null) {
            optimalRenderScale = renderScale;
        }

        final List<Feature> pFeatureList = scaleToPFeatureListMap.get(optimalRenderScale);
        final List<Feature> qFeatureList = scaleToQFeatureListMap.get(optimalRenderScale);

        Float optimalRod = null;

        float rod = 0.5f;
        float previousRod = rod;
        int previousInlierCount = 0;
        int inlierCount = 0;

        CanvasFeatureMatcher matcher;
        CanvasFeatureMatchResult matchResult;
        while ((optimalRod == null) && (rod > 0f) && (rod < 1.1f)) {

            LOG.info("run: testing match rod {}", rod);

            parameters.matchDerivation.matchRod = rod;

            matcher = new CanvasFeatureMatcher(parameters.matchDerivation);
            matchResult = matcher.deriveMatchResult(pFeatureList, qFeatureList);

            inlierCount = matchResult.getInlierPointMatchList().size();

            if (inlierCount < parameters.matchDerivation.matchMinNumInliers) {

                if (previousInlierCount >= parameters.matchDerivation.matchMinNumInliers) {
                    optimalRod = previousRod;
                    inlierCount = previousInlierCount;
                } else {
                    previousRod = rod;
                    rod += parameters.matchRodStep;
                }

            } else if (inlierCount > parameters.matchDerivation.matchMaxNumInliers) {

                previousRod = rod;
                rod -= parameters.matchRodStep;

            } else {

                optimalRod = rod;

            }

            previousInlierCount = inlierCount;
        }

        LOG.info("run: summary\n\n\n\n");

        LOG.info("run: optimal render scale {} results in {} p features and {} q features",
                 optimalRenderScale, pFeatureList.size(), qFeatureList.size());

        LOG.info("run: optimal match ratio of distances {} results in {} inlier matches\n\n\n\n",
                 optimalRod, inlierCount);

        LOG.info("run: exit");
    }

    private int extractFeaturesForScale(final double renderScale) {

        LOG.info("extractFeaturesForScale: entry, scale={}", renderScale);

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = parameters.featureExtraction.fdSize;
        siftParameters.steps = parameters.featureExtraction.steps;

        final CanvasFeatureExtractor extractor = new CanvasFeatureExtractor(siftParameters,
                                                                            renderScale - 0.02,
                                                                            renderScale + 0.02,
                                                                            true);

        final RenderParameters pRenderParameters = loadRenderParameters(pRenderParametersUrl, renderScale);

        List<Feature> pFeatureList;
        try {
            pFeatureList = extractor.extractFeatures(pRenderParameters, null);
        } catch (final Throwable t) {
            pFeatureList = new ArrayList<>();
            LOG.warn("skipping p canvas renderScale " + renderScale + " because of exception", t);
        }

        scaleToPFeatureListMap.put(renderScale, pFeatureList);

        final RenderParameters qRenderParameters = loadRenderParameters(qRenderParametersUrl, renderScale);

        List<Feature> qFeatureList;
        try {
            qFeatureList = extractor.extractFeatures(qRenderParameters, null);
        } catch (final Throwable t) {
            qFeatureList = new ArrayList<>();
            LOG.warn("skipping q canvas renderScale " + renderScale + " because of exception", t);
        }

        scaleToQFeatureListMap.put(renderScale, qFeatureList);

        return Math.min(pFeatureList.size(), qFeatureList.size());
    }

    private RenderParameters loadRenderParameters(final String url,
                                                  final double renderScale) {
        final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);
        renderParameters.setScale(renderScale);
        renderParameters.setDoFilter(true);
        renderParameters.setExcludeMask(true);
        return renderParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(PointMatchOptimizerClient.class);
}
