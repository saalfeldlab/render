package org.janelia.render.client;

import java.util.Collections;
import java.util.List;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.FeatureStorageParameters;
import org.junit.Test;

/**
 * Tests the {@link SIFTPointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class SIFTPointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new SIFTPointMatchClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            runTestWithExternalDependencies();
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private static void runTestWithExternalDependencies()
            throws Exception {

        final String urlTemplateString = "{baseDataUrl}/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/{id}/render-parameters";
        final CanvasId pCanvasId = new CanvasId("26101.0", "19-02-21_105501_0-0-0.26101.0");
        final CanvasId qCanvasId = new CanvasId("26102.0", "19-02-21_161150_0-0-0.26102.0");
        final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(pCanvasId, qCanvasId);
        final List<OrderedCanvasIdPair> pairList = Collections.singletonList(pair);

        final RenderableCanvasIdPairs renderableCanvasIdPairs = new RenderableCanvasIdPairs(urlTemplateString,
                                                                                            pairList);

        final SIFTPointMatchClient.Parameters parameters = new SIFTPointMatchClient.Parameters();
        final SIFTPointMatchClient client = new SIFTPointMatchClient(parameters);

        parameters.matchClient.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.matchClient.owner = "Z1217_19m";
        parameters.matchClient.collection = "test_gd_matches_Sec07";

        parameters.featureRender.renderScale = 0.15;
        parameters.featureRender.renderWithFilter = true;
        parameters.featureRender.renderWithoutMask = false;
        parameters.featureRender.fillWithNoise = true;

        parameters.featureRenderClip = new FeatureRenderClipParameters(); // no clip

        parameters.featureExtraction.fdSize = 4;
        parameters.featureExtraction.minScale = 0.25;
        parameters.featureExtraction.maxScale = 1.0;
        parameters.featureExtraction.steps = 3;

        parameters.matchDerivation.matchRod = 0.92f;
        parameters.matchDerivation.matchModelType = ModelType.AFFINE;
        parameters.matchDerivation.matchRegularizerModelType = ModelType.RIGID;
        parameters.matchDerivation.matchInterpolatedModelLambda = 0.25;
        parameters.matchDerivation.matchIterations = 1000;
        parameters.matchDerivation.matchMaxEpsilon = 50.0f;
        parameters.matchDerivation.matchMinInlierRatio = 0.0f;
        parameters.matchDerivation.matchMinNumInliers = 10;
        parameters.matchDerivation.matchMaxTrust = 4.0;
        parameters.matchDerivation.matchFilter = MatchFilter.FilterType.SINGLE_SET;

        parameters.featureStorage = new FeatureStorageParameters(); // use defaults

        final GeometricDescriptorParameters gdParameters = new GeometricDescriptorParameters();
        gdParameters.numberOfNeighbors = 3;
        gdParameters.redundancy = 1;
        gdParameters.significance = 2.0;
        gdParameters.sigma = 2.04;
        gdParameters.threshold = 0.008;
        gdParameters.localization = GeometricDescriptorParameters.LocalizationFitType.NONE; // THREE_D_QUADRATIC
        gdParameters.lookForMinima = true;
        gdParameters.lookForMaxima = false;
        gdParameters.fullScaleBlockRadius = 0.0; // 300.0;
        gdParameters.fullScaleNonMaxSuppressionRadius = 120.0; // 60.0
        gdParameters.gdStoredMatchWeight = 0.4;

        final MatchDerivationParameters gdMatchParameters = new MatchDerivationParameters();
        gdMatchParameters.matchModelType = ModelType.RIGID;
        gdMatchParameters.matchIterations = 1000;
        gdMatchParameters.matchMaxEpsilon = 20.0f;
        gdMatchParameters.matchMinInlierRatio = 0.0f;
        gdMatchParameters.matchMinNumInliers = 4;
        gdMatchParameters.matchMaxTrust = 3.0;
        gdMatchParameters.matchFilter = MatchFilter.FilterType.SINGLE_SET;

        parameters.geometricDescriptorAndMatch.renderScale = 0.25; // 0.5
        parameters.geometricDescriptorAndMatch.geometricDescriptorParameters = gdParameters;
        parameters.geometricDescriptorAndMatch.matchDerivationParameters = gdMatchParameters;

        parameters.geometricDescriptorAndMatch.minCombinedInliers = 600;
        parameters.geometricDescriptorAndMatch.minCombinedCoverageAreaPercentage = 48.5;

        parameters.geometricDescriptorAndMatch.validateAndSetDefaults();

        client.generateMatchesForPairs(renderableCanvasIdPairs,
                                       parameters.matchClient.baseDataUrl,
                                       parameters.featureRender,
                                       parameters.featureRenderClip,
                                       parameters.featureExtraction,
                                       parameters.featureStorage,
                                       parameters.matchDerivation);
    }

}
