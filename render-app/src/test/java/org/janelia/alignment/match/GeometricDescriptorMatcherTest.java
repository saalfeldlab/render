package org.janelia.alignment.match;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.ShortRenderer;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Ignore;
import org.junit.Test;

import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.spim.segmentation.InteractiveDoG;
import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import plugin.DescriptorParameters;

/**
 * Runs peak extraction for two tiles.
 *
 * Comment Ignore annotation below to run tests using JUnit.
 */
@Ignore
public class GeometricDescriptorMatcherTest {

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
        // setup test parameters ...

        final CanvasPeakExtractor extractor = new CanvasPeakExtractor( getInitialDescriptorParameters() );
        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher( getMatchFilterParameters() );

        final double renderScale = 0.25;

        final String tileId1 = "19-02-21_105501_0-0-0.26101.0";
        final String tileId2 = "19-02-21_161150_0-0-0.26102.0";

        // -------------------------------------------------------------------
        // run test ...

        final BufferedImage image1 = renderTile(tileId1, renderScale, false);
        final BufferedImage image2 = renderTile(tileId2, renderScale, false);

        List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks1 = extractor.extractPeaksFromImage(image1);
        List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks2 = extractor.extractPeaksFromImage(image2);

        /*
        for ( int i = canvasPeaks1.size() - 1; i >= 0; --i )
        	if ( canvasPeaks1.get( i ).getPosition( 0 ) < 2200 )
        		canvasPeaks1.remove( i );

        for ( int i = canvasPeaks2.size() - 1; i >= 0; --i )
        	if ( canvasPeaks2.get( i ).getPosition( 0 ) < 2200 )
        		canvasPeaks2.remove( i );
		*/
        canvasPeaks1 = thinOut( canvasPeaks1, 0, 10, false );
        canvasPeaks2 = thinOut( canvasPeaks2, 0, 10, false );

        System.out.println( canvasPeaks1.size() );
        System.out.println( canvasPeaks2.size() );

        final ImagePlus ip1 = new ImagePlus(tileId1, image1);
        final ImagePlus ip2 = new ImagePlus(tileId2, image2);

        setPointRois( ip1, canvasPeaks1 );
        setPointRois( ip2, canvasPeaks2 );

        ip1.show();
        ip2.show();

        // important, we need to use the adjusted parameters here as well
        final CanvasFeatureMatchResult result =
                matcher.deriveGeometricDescriptorMatchResult(canvasPeaks1, canvasPeaks2, extractor.getAdjustedParameters() );

        // NOTE: assumes matchFilter is SINGLE_SET
        final List<PointMatch> inliers = result.getInlierPointMatchList();

        final ImagePlus ipnew1 = new ImagePlus(tileId1, image1);
        final ImagePlus ipnew2 = new ImagePlus(tileId2, image2);

        setPointRois( ipnew1, ipnew2, inliers );

        ipnew1.show();
        ipnew2.show();

        SimpleMultiThreading.threadHaltUnClean();

        // -------------------------------------------------------------------
        // display results ...
    }

    public static List< DifferenceOfGaussianPeak< FloatType > > thinOut( final List< DifferenceOfGaussianPeak< FloatType > > canvasPeaks, final double minDistance, final double maxDistance, final boolean keepRange )
    {
		// assemble the list of points (we need two lists as the KDTree sorts the list)
		// we assume that the order of list2 is preserved
		final List< RealPoint > list1 = new ArrayList< RealPoint >();
		final List< RealPoint > list2 = new ArrayList< RealPoint >();

		for ( final DifferenceOfGaussianPeak<FloatType> ip : canvasPeaks )
		{
			list1.add ( new RealPoint(
					ip.getSubPixelPosition( 0 ),
					ip.getSubPixelPosition( 1 ) ) );

			list2.add ( new RealPoint(
					ip.getSubPixelPosition( 0 ),
					ip.getSubPixelPosition( 1 ) ) );
		}

		// make the KDTree
		final KDTree< RealPoint > tree = new KDTree< RealPoint >( list1, list1 );

		// Nearest neighbor for each point, populate the new list
		final KNearestNeighborSearchOnKDTree< RealPoint > nn = new KNearestNeighborSearchOnKDTree< RealPoint >( tree, 2 );
		final List< DifferenceOfGaussianPeak< FloatType > > newIPs = new ArrayList<>();

		for ( int j = 0; j < list2.size(); ++j )
		{
			final RealPoint p = list2.get( j );
			nn.search( p );
			
			// first nearest neighbor is the point itself, we need the second nearest
			final double d = nn.getDistance( 1 );
			
			if ( ( keepRange && d >= minDistance && d <= maxDistance ) || ( !keepRange && ( d < minDistance || d > maxDistance ) ) )
				newIPs.add( canvasPeaks.get( j ) );
		}

		return newIPs;
    }

	protected static void setPointRois( final ImagePlus imp1, final ImagePlus imp2, final List<PointMatch> inliers )
	{
		final ArrayList<Point> list1 = new ArrayList<Point>();
		final ArrayList<Point> list2 = new ArrayList<Point>();

		PointMatch.sourcePoints( inliers, list1 );
		PointMatch.targetPoints( inliers, list2 );
		
		PointRoi sourcePoints = mpicbg.ij.util.Util.pointsToPointRoi(list1);
		PointRoi targetPoints = mpicbg.ij.util.Util.pointsToPointRoi(list2);
		
		imp1.setRoi( sourcePoints );
		imp2.setRoi( targetPoints );
		
	}

	protected static void setPointRois( final ImagePlus imp1, List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks )
	{
		final ArrayList<Point> list1 = new ArrayList<Point>();

		for ( final DifferenceOfGaussianPeak< FloatType > p : canvasPeaks )
			list1.add( new Point( new double[] { p.getSubPixelPosition( 0 ), p.getSubPixelPosition( 1 ) } ) );

		PointRoi points = mpicbg.ij.util.Util.pointsToPointRoi( list1 );
		
		imp1.setRoi( points );
	}


    protected static DescriptorParameters getInitialDescriptorParameters()
    {
        final DescriptorParameters descriptorParameters = new DescriptorParameters();

        descriptorParameters.dimensionality = 2; // always 2
        descriptorParameters.numNeighbors = 3;
        descriptorParameters.redundancy = 1;
        descriptorParameters.significance = 2;

        // TODO: make sure "sigma" mentioned in wiki page test set is saved to correct parameter here
        descriptorParameters.sigma1 = 2.04;
        descriptorParameters.sigma2 = InteractiveDoG.computeSigma2( (float)descriptorParameters.sigma1, InteractiveDoG.standardSensitivity );

        descriptorParameters.threshold = 0.008;
        descriptorParameters.lookForMinima = true;
        descriptorParameters.lookForMaxima = false;

        return descriptorParameters;
    }

    protected static MatchDerivationParameters getMatchFilterParameters()
    {
        final MatchDerivationParameters matchFilterParameters = new MatchDerivationParameters();
        matchFilterParameters.matchModelType = ModelType.RIGID;
        matchFilterParameters.matchIterations = 1000;
        matchFilterParameters.matchMaxEpsilon = 20.0f;
        matchFilterParameters.matchMinInlierRatio = 0.0f;
        matchFilterParameters.matchMinNumInliers = 4;
        matchFilterParameters.matchMaxTrust = 3.0;
        matchFilterParameters.matchFilter = CanvasFeatureMatcher.FilterType.SINGLE_SET; // warning: changing this will break getInlierPointMatchList call below

        return matchFilterParameters;
    }

    protected static BufferedImage renderTile(final String tileId,
                                     final double renderScale,
                                     final boolean filter) {

        final String baseTileUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1/owner/Z1217_19m/project/Sec07/stack/v1_acquire/tile/";
        final String urlSuffix = "/render-parameters?scale=" + renderScale;
        // TODO: add &fillWithNoise=true ?
        // TODO: add &excludeMask=true ?
        final String url = baseTileUrl + tileId + urlSuffix;

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);
        renderParameters.setDoFilter( filter );
        renderParameters.initializeDerivedValues();

        renderParameters.validate();

        // RGB
        //final BufferedImage bufferedImage = renderParameters.openTargetImage();
        //ArgbRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        // 8 bit (almost identical to RGB when converted to gray)
        final BufferedImage bufferedImage = renderParameters.openTargetImage( BufferedImage.TYPE_BYTE_GRAY );
        ShortRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        // 16 bit
        //final BufferedImage bufferedImage = renderParameters.openTargetImage( BufferedImage.TYPE_USHORT_GRAY );
        //ShortRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        //new ImageJ();
        //new ImagePlus( "", bufferedImage ).show();
        //SimpleMultiThreading.threadHaltUnClean();

        return bufferedImage;
    }

}
