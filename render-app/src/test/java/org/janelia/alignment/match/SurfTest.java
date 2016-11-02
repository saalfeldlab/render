package org.janelia.alignment.match;

import org.janelia.alignment.RenderParameters;
import org.junit.Ignore;
import org.junit.Test;

import boofcv.struct.image.GrayF32;

/**
 * Hack to exercise SURF feature extraction and point match code.
 * Uncomment Ignore annotation to run.
 *
 * @author Eric Trautman
 */
@Ignore
public class SurfTest {

    @Test
    public void runTests() throws Exception {

        testTwoPairs("150311140241101032.3334.0", "150311140241102032.3334.0", "150311140241101033.3334.0");
        testTwoPairs("151217112333012136.525.0", "151217112333013136.525.0", "151217112333012137.525.0");

    }

    private void testTwoPairs(final String pTileId,
                              final String qTileId,
                              final String q2TileId) throws Exception {

        final CanvasSurfFeatureExtractor featureExtractor =
                new CanvasSurfFeatureExtractor<>(CanvasSurfFeatureExtractor.DEFAULT_CONFIG, true, GrayF32.class);
        final SurfFeatures pFeatures = featureExtractor.extractFeatures(getRenderParameters(pTileId), null);
        final SurfFeatures qFeatures = featureExtractor.extractFeatures(getRenderParameters(qTileId), null);
        final SurfFeatures q2Features = featureExtractor.extractFeatures(getRenderParameters(q2TileId), null);

        final CanvasMatchFilter featureFilter = new CanvasMatchFilter(20.0f, 0.0f, 10, null, true);
        final CanvasSurfFeatureMatcher featureMatcher = new CanvasSurfFeatureMatcher();
        final CanvasFeatureMatchResult matchResult = featureMatcher.deriveMatchResult(pFeatures, qFeatures, featureFilter);

        final Matches m = CanvasFeatureMatchResult.convertPointMatchListToMatches(matchResult.getInlierPointMatchList(), 1.0);

        System.out.println(new CanvasMatches("A", pTileId, "A", qTileId, m).toJson());

        final CanvasFeatureMatchResult matchResult2 = featureMatcher.deriveMatchResult(pFeatures, q2Features, featureFilter);

        final Matches m2 = CanvasFeatureMatchResult.convertPointMatchListToMatches(matchResult2.getInlierPointMatchList(), 1.0);

        System.out.println(new CanvasMatches("A", pTileId, "A", q2TileId, m2).toJson());
    }

    private RenderParameters getRenderParameters(final String tileId) {
        final String baseUrl = "http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/";
        final String urlSuffix = "/render-parameters?excludeMask=true&normalizeForMatching=true&width=2760&height=2330";
        return RenderParameters.loadFromUrl(baseUrl + tileId + urlSuffix);
    }

}