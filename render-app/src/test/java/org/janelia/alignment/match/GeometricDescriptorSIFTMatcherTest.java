package org.janelia.alignment.match;

import java.awt.image.BufferedImage;
import java.util.List;

import org.janelia.alignment.match.CanvasFeatureMatcher.FilterType;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.PointMatch;

/**
 * Runs peak extraction for two tiles.
 *
 * Comment Ignore annotation below to run tests using JUnit.
 */
@Ignore
public class GeometricDescriptorSIFTMatcherTest {

    @Test
    public void testMatch() {

        // -------------------------------------------------------------------
        // NOTES:
        //
        //   1. make sure /groups/flyem/data is mounted
        //      -- on Mac, after mount need to ln -s /Volumes/flyemdata /groups/flyem/data
        //
        //   2. update test parameters
        //      -- tests assume tiles are in Z1217_19m :: Sec07 :: v1_acquire  stack

        // -------------------------------------------------------------------

    	// RUN SIFT HERE
        final String tileId1 = "19-02-21_105501_0-0-0.26101.0";
        final String tileId2 = "19-02-21_161150_0-0-0.26102.0";

        // -------------------------------------------------------------------
        // run test ...

        /*
		renderScale: 0.15 fdSize: 4 minScale: 0.25 maxScale: 1 steps: 3
		Match Parameters:	modelType: AFFINE regularizerModelType: RIGID interpolatedModelLambda: 0.25
		ROD: 0.92 iterations: 1000 maxEpsilon: 50 minInlierRatio: 0
		minNumInliers: 10 maxTrust: 4 filter: SINGLE_SET
         */

        // RUN SIFT
        /*FeatureExtractionParameters siftParam = new FeatureExtractionParameters();
        siftParam.fdSize = 4;
        siftParam.minScale = 0.25;
        siftParam.maxScale = 1.0;
        siftParam.steps = 3;*/
        final double renderScale = 0.15;

        final BufferedImage image1 = GeometricDescriptorMatcherTest.renderTile(tileId1, renderScale, true);
        final BufferedImage image2 = GeometricDescriptorMatcherTest.renderTile(tileId2, renderScale, true);

        final FloatArray2DSIFT.Param coreSiftParameters = new FloatArray2DSIFT.Param();
        coreSiftParameters.fdSize = 4;
        coreSiftParameters.steps = 3;
        final double minScale = 0.25;
        final double maxScale = 1.0;

        CanvasFeatureExtractor canvasFeatureExtractor = new CanvasFeatureExtractor( coreSiftParameters, minScale, maxScale, true );
        List< Feature > f1 = canvasFeatureExtractor.extractFeaturesFromImage( image1 );
        List< Feature > f2 = canvasFeatureExtractor.extractFeaturesFromImage( image2 );

    	// GET INLIERS
        MatchDerivationParameters ransacParam = new MatchDerivationParameters( 0.92f, ModelType.AFFINE, 1000, 50, 0, 10, 4, null, FilterType.SINGLE_SET );
        ransacParam.matchRegularizerModelType = ModelType.RIGID;
        ransacParam.matchInterpolatedModelLambda = 0.25;

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(ransacParam);

        final CanvasFeatureMatchResult result =
        		matcher.deriveSIFTMatchResult( f1, f2 );

        // NOTE: assumes matchFilter is SINGLE_SET (supports multi-model matching)
        final List<PointMatch> inliers = result.getInlierPointMatchLists().get( 0 );

        LOG.debug( "#inliers: " + inliers.size() );
 
        // NOW Run Geometric Descriptor matching using the set inliers for masking

        for ( final PointMatch pm : inliers )
        {
        	pm.getP1().getL();
        	pm.getP2().getL();
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(GeometricDescriptorSIFTMatcherTest.class);
}
