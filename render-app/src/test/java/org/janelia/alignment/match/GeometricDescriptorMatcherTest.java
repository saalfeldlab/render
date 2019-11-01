package org.janelia.alignment.match;

import java.awt.image.BufferedImage;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.ShortRenderer;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Ignore;
import org.junit.Test;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.PointMatch;
import mpicbg.spim.segmentation.InteractiveDoG;
import plugin.DescriptorParameters;

/**
 * Runs peak extraction for two tiles.
 *
 * Comment Ignore annotation below to run tests using JUnit.
 */
//@Ignore
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

        final MatchDerivationParameters matchFilterParameters = new MatchDerivationParameters();
        matchFilterParameters.matchModelType = ModelType.RIGID;
        matchFilterParameters.matchIterations = 1000;
        matchFilterParameters.matchMaxEpsilon = 20.0f;
        matchFilterParameters.matchMinInlierRatio = 0.0f;
        matchFilterParameters.matchMinNumInliers = 4;
        matchFilterParameters.matchMaxTrust = 3.0;
        matchFilterParameters.matchFilter = CanvasFeatureMatcher.FilterType.SINGLE_SET; // warning: changing this will break getInlierPointMatchList call below

        final double renderScale = 0.25;

        final String tileId1 = "19-02-21_105501_0-0-0.26101.0";
        final String tileId2 = "19-02-21_161150_0-0-0.26102.0";

        // -------------------------------------------------------------------
        // run test ...

        final BufferedImage image1 = renderTile(tileId1, renderScale, false);
        final BufferedImage image2 = renderTile(tileId2, renderScale, false);

        final CanvasPeakExtractor extractor = new CanvasPeakExtractor(descriptorParameters);

        // TODO: fix array index out of bounds error
        
        final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks1 = extractor.extractPeaksFromImage(image1);
        final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks2 = extractor.extractPeaksFromImage(image2);

        for ( int i = canvasPeaks1.size() - 1; i >= 0; --i )
        	if ( canvasPeaks1.get( i ).getPosition( 0 ) < 2200 )
        		canvasPeaks1.remove( i );

        for ( int i = canvasPeaks2.size() - 1; i >= 0; --i )
        	if ( canvasPeaks2.get( i ).getPosition( 0 ) < 2200 )
        		canvasPeaks2.remove( i );

        System.out.println( canvasPeaks1.size() );
        System.out.println( canvasPeaks2.size() );

        final CanvasFeatureMatcher matcher = new CanvasFeatureMatcher(matchFilterParameters);

        // important, we need to use the adjusted parameters here as well
        final CanvasFeatureMatchResult result =
                matcher.deriveGeometricDescriptorMatchResult(canvasPeaks1, canvasPeaks2, extractor.getAdjustedParameters() );

        // NOTE: assumes matchFilter is SINGLE_SET
        final List<PointMatch> inliers = result.getInlierPointMatchList();

        //SimpleMultiThreading.threadHaltUnClean();

        // -------------------------------------------------------------------
        // display results ...

        final ImagePlus ip1 = new ImagePlus(tileId1, image1);
        final ImagePlus ip2 = new ImagePlus(tileId2, image2);

        // TODO: render match points
        
        ip1.show();
        ip2.show();
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
