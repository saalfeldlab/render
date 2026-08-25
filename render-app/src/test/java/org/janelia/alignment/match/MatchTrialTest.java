package org.janelia.alignment.match;

import java.io.StringReader;

import org.janelia.alignment.match.parameters.MatchTrialParameters;
import org.janelia.alignment.match.stage.StageMatchingStats;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the {@link MatchTrial} class.
 *
 * @author Eric Trautman
 */
public class MatchTrialTest {

    @Test
    public void testConstructors() {
        final MatchTrial matchTrial = new MatchTrial();
        assertNull(matchTrial.getStats());

        final StageMatchingStats stats = new StageMatchingStats();
        assertNotNull(stats);
    }

    public static void main(final String[] args) {

        final String parametersJson = args.length == 0 ? CROSS_GD_TRIAL_A_JSON : MONTAGE_TRIAL_A_JSON;

        final MatchTrialParameters trialParameters = MatchTrialParameters.fromJson(new StringReader(parametersJson));

        final MatchTrial matchTrial = new MatchTrial(trialParameters);
        matchTrial.deriveResults(ImageProcessorCache.DISABLED_CACHE);

        System.out.println(matchTrial.toJson());
    }

    private static final  String CROSS_GD_TRIAL_A_JSON = """
                    {
                    "featureAndMatchParameters": {
                    "siftFeatureParameters": {
                    "fdSize": 4,
                    "minScale": 0.125,
                    "maxScale": 1,
                    "steps": 5
                    },
                    "matchDerivationParameters": {
                    "matchRod": 0.92,
                    "matchModelType": "AFFINE",
                    "matchRegularizerModelType": "RIGID",
                    "matchInterpolatedModelLambda": 0.25,
                    "matchIterations": 1000,
                    "matchMaxEpsilon": 3,
                    "matchMinInlierRatio": 0,
                    "matchMinNumInliers": 20,
                    "matchMaxTrust": 4,
                    "matchFilter": "SINGLE_SET",
                    "matchFullScaleCoverageRadius": 300
                    }
                    },
                    "pRenderParametersUrl": "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-24_090152_0-0-1.29351.0/render-parameters?filter=true&scale=0.05",
                    "qRenderParametersUrl": "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-24_090517_0-0-0.29352.0/render-parameters?filter=true&scale=0.05",
                    "geometricDescriptorAndMatchFilterParameters": {
                    "gdEnabled": true,
                    "renderScale": 0.1,
                    "renderWithFilter": false,
                    "geometricDescriptorParameters": {
                    "numberOfNeighbors": 3,
                    "redundancy": 1,
                    "significance": 2,
                    "sigma": 2.04,
                    "threshold": 0.006,
                    "localization": "THREE_D_QUADRATIC",
                    "lookForMinima": true,
                    "lookForMaxima": false,
                    "similarOrientation": true,
                    "fullScaleBlockRadius": 500,
                    "fullScaleNonMaxSuppressionRadius": 100,
                    "gdStoredMatchWeight": 0.4
                    },
                    "matchDerivationParameters": {
                    "matchRod": 0.92,
                    "matchModelType": "AFFINE",
                    "matchRegularizerModelType": "RIGID",
                    "matchInterpolatedModelLambda": 0.25,
                    "matchIterations": 1000,
                    "matchMaxEpsilon": 2,
                    "matchMinInlierRatio": 0,
                    "matchMinNumInliers": 20,
                    "matchMaxTrust": 3,
                    "matchFilter": "SINGLE_SET",
                    "matchFullScaleCoverageRadius": 300
                    },
                    "runGeoRegardlessOfSiftResults": false
                    }
                    }""";

    private static final String MONTAGE_TRIAL_A_JSON = """
                    {
                      "featureAndMatchParameters": {
                        "siftFeatureParameters": {
                          "fdSize": 4,
                          "minScale": 0.25,
                          "maxScale": 1,
                          "steps": 5
                          },
                        "matchDerivationParameters": {
                          "matchRod": 0.92,
                          "matchModelType": "RIGID",
                          "matchRegularizerModelType": "TRANSLATION",
                          "matchInterpolatedModelLambda": 0.25,
                          "matchIterations": 1000,
                          "matchMaxEpsilon": 30,
                          "matchMinInlierRatio": 0,
                          "matchMinNumInliers": 10,
                          "matchMaxTrust": 4,
                          "matchFilter": "SINGLE_SET",
                          "matchFullScaleCoverageRadius": 300
                        },
                        "pClipPosition": "LEFT",
                        "clipPixels": 500
                      },
                      "pRenderParametersUrl": "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-07_212459_0-0-1.10001.0/render-parameters?filter=true&scale=0.3",
                      "qRenderParametersUrl": "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/19-02-07_212459_0-0-2.10001.0/render-parameters?filter=true&scale=0.3"
                    }""";

}
