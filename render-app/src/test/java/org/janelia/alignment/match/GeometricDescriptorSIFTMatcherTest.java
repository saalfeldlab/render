package org.janelia.alignment.match;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasFeatureMatcher.FilterType;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImagePlus;
import ij.gui.PointRoi;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

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

        // blocking geometric descriptor matching in the following radius (original image scale)
        final double blockRadiusFull = 270;

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
        final double renderScaleSIFT = 0.15;
        final double blockRadiusSIFT = blockRadiusFull * renderScaleSIFT;

        final BufferedImage imageSIFT1 = GeometricDescriptorMatcherTest.renderTile(tileId1, renderScaleSIFT, true);
        final BufferedImage imageSIFT2 = GeometricDescriptorMatcherTest.renderTile(tileId2, renderScaleSIFT, true);

        final FloatArray2DSIFT.Param coreSiftParameters = new FloatArray2DSIFT.Param();
        coreSiftParameters.fdSize = 4;
        coreSiftParameters.steps = 3;
        final double minScale = 0.25;
        final double maxScale = 1.0;

        CanvasFeatureExtractor canvasFeatureExtractor = new CanvasFeatureExtractor( coreSiftParameters, minScale, maxScale, true );
        List< Feature > f1 = canvasFeatureExtractor.extractFeaturesFromImage( imageSIFT1 );
        List< Feature > f2 = canvasFeatureExtractor.extractFeaturesFromImage( imageSIFT2 );

    	// GET INLIERS
        MatchDerivationParameters ransacParam = new MatchDerivationParameters( 0.92f, ModelType.AFFINE, 1000, 50, 0, 10, 4, null, FilterType.SINGLE_SET );
        ransacParam.matchRegularizerModelType = ModelType.RIGID;
        ransacParam.matchInterpolatedModelLambda = 0.25;

        final CanvasFeatureMatcher matcherSIFT = new CanvasFeatureMatcher(ransacParam);

        final CanvasFeatureMatchResult result =
        		matcherSIFT.deriveSIFTMatchResult( f1, f2 );

        // NOTE: assumes matchFilter is SINGLE_SET (supports multi-model matching)
        final List<PointMatch> inliers = result.getInlierPointMatchLists().get( 0 );

        LOG.debug( "#inliers: " + inliers.size() );

        final ImagePlus impSIFT1 = new ImagePlus(tileId1 + "_SIFT", imageSIFT1);
        final ImagePlus impSIFT2 = new ImagePlus(tileId2 + "_SIFT", imageSIFT2);

        drawBlockedRegions( impSIFT1, impSIFT2, blockRadiusSIFT, inliers );
        GeometricDescriptorMatcherTest.setPointRois( impSIFT1, impSIFT2, inliers );

        impSIFT1.show();
        impSIFT2.show();

        //
        // NOW Run Geometric Descriptor matching using the set inliers for masking
        //

        // Geometric descriptor parameters
        final double renderScaleGeo = 0.25;
        final double blockRadiusGeo = blockRadiusFull * renderScaleGeo;

        final CanvasPeakExtractor extractorGeo = new CanvasPeakExtractor( GeometricDescriptorMatcherTest.getInitialDescriptorParameters() );
        final CanvasFeatureMatcher matcherGeo = new CanvasFeatureMatcher( GeometricDescriptorMatcherTest.getMatchFilterParameters() );


        // -------------------------------------------------------------------
        // run test ...

        final BufferedImage imageGeo1 = GeometricDescriptorMatcherTest.renderTile(tileId1, renderScaleGeo, false);
        final BufferedImage imageGeo2 = GeometricDescriptorMatcherTest.renderTile(tileId2, renderScaleGeo, false);

        //List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks1 = extractorGeo.extractPeaksFromImage(imageGeo1);
        //List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks2 = extractorGeo.extractPeaksFromImage(imageGeo2);

        final ImagePlus impGeo1 = new ImagePlus(tileId1 + "_Geo", imageGeo1);
        final ImagePlus impGeo2 = new ImagePlus(tileId2 + "_Geo", imageGeo2);

        final Pair< ArrayList< Point >, ArrayList< Point > > adjustedInliers = adjustInliers( inliers, renderScaleSIFT, renderScaleGeo );

		drawBlockedRegions( adjustedInliers.getA(), blockRadiusGeo, impGeo1 );
		drawBlockedRegions( adjustedInliers.getB(), blockRadiusGeo, impGeo2 );

        //drawBlockedRegions( impSIFT1, impSIFT2, blockRadiusSIFT, inliers );
        GeometricDescriptorMatcherTest.setPointRois( adjustedInliers.getA(), impGeo1 );
        GeometricDescriptorMatcherTest.setPointRois( adjustedInliers.getB(), impGeo2 );

        impGeo1.show();
        impGeo2.show();

        SimpleMultiThreading.threadHaltUnClean();
    }

    protected static Pair< ArrayList< Point >, ArrayList< Point > > adjustInliers( final List<PointMatch> inliers, final double scaleSIFT, final double scaleGeo )
    {
    	final ArrayList< Point > sourcePoints = new ArrayList<>();
    	final ArrayList< Point > targetPoints = new ArrayList<>();

    	PointMatch.sourcePoints( inliers, sourcePoints );
    	PointMatch.targetPoints( inliers, targetPoints );

    	// TODO: do not ignore world coordinates
    	for ( final Point p : sourcePoints )
    	{
    		p.getL()[ 0 ] = (p.getL()[ 0 ] / scaleSIFT) * scaleGeo;
    		p.getL()[ 1 ] = (p.getL()[ 1 ] / scaleSIFT) * scaleGeo;
    	}

    	// TODO: do not ignore world coordinates
    	for ( final Point p : targetPoints )
    	{
    		p.getL()[ 0 ] = (p.getL()[ 0 ] / scaleSIFT) * scaleGeo;
    		p.getL()[ 1 ] = (p.getL()[ 1 ] / scaleSIFT) * scaleGeo;
    	}

    	return new ValuePair< ArrayList<Point>, ArrayList<Point> >( sourcePoints, targetPoints );
    }

	protected static void drawBlockedRegions( final ImagePlus imp1, final ImagePlus imp2, final double radius, final List<PointMatch> inliers )
	{
        final ArrayList<Point> list1 = new ArrayList<Point>();
        final ArrayList<Point> list2 = new ArrayList<Point>();

		PointMatch.sourcePoints( inliers, list1 );
		PointMatch.targetPoints( inliers, list2 );

		drawBlockedRegions( list1, radius, imp1 );
		drawBlockedRegions( list2, radius, imp2 );
	}

    public static void drawBlockedRegions( final ArrayList<Point> inliers, final double radius, final ImagePlus imp )
    {
		// assemble the list of points (we need two lists as the KDTree sorts the list)
		// we assume that the order of list2 is preserved
		final List< RealPoint > list1 = new ArrayList< RealPoint >();
		final List< RealPoint > list2 = new ArrayList< RealPoint >();

		for ( final Point p : inliers )
		{
			list1.add ( new RealPoint( p.getL()[ 0 ], p.getL()[ 1 ] ) );
			list2.add ( new RealPoint( p.getL()[ 0 ], p.getL()[ 1 ] ) );
		}

		// make the KDTree
		final KDTree< RealPoint > tree = new KDTree< RealPoint >( list1, list1 );

		// Nearest neighbor for each point, populate the new list
		final NearestNeighborSearchOnKDTree< RealPoint > nn = new NearestNeighborSearchOnKDTree< RealPoint >( tree );

		for ( int y = 0; y < imp.getHeight(); ++y )
			for ( int x = 0; x < imp.getWidth(); ++x )
			{
				final RealPoint p = new RealPoint( x, y );
				nn.search( p );
	
				// first nearest neighbor is the point itself, we need the second nearest
				final double d = nn.getDistance();
	
				if ( d <= radius )
					imp.getProcessor().set( x, y, 255 );
			}
    }

    private static final Logger LOG = LoggerFactory.getLogger(GeometricDescriptorSIFTMatcherTest.class);
}
