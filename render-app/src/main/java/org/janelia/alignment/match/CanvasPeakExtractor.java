package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.util.Timer;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import plugin.DescriptorParameters;

import static process.Matching.extractCandidates;

/**
 * Extracts peaks from a canvas (specified by render parameters) using {@link process.Matching#extractCandidates}.
 */
public class CanvasPeakExtractor
        implements Serializable {

    private final DescriptorParameters coreDescriptorParameters;

    /**
     * Sets up everything that is needed to extract the peak list for a canvas.
     *
     * @param  descriptorParameters  core descriptor parameters for peak extraction.
     */
    CanvasPeakExtractor(final DescriptorParameters descriptorParameters) {

        // clone provided parameters since they (might?) get modified during peak extraction
        this.coreDescriptorParameters = cloneParametersForRenderUseCase(descriptorParameters);
    }

    public DescriptorParameters getAdjustedParameters() { return coreDescriptorParameters; }

    /**
     * Extract Gaussian peaks from canvas built from specified render parameters.
     *
     * @param  renderParameters  parameters for building canvas.
     * @param  renderFile        file to persist rendered canvas (for debugging).
     *                           Specify as null to skip debug persistence.
     *
     * @return list of peaks.
     *
     * @throws IllegalArgumentException
     *   if the specified render parameters are invalid.
     *
     * @throws IllegalStateException
     *   if the specified render parameters have not been initialized or no features are found.
     */
    public List<DifferenceOfGaussianPeak<FloatType>> extractPeaks(final RenderParameters renderParameters,
                                                                  final File renderFile)
            throws IllegalArgumentException, IllegalStateException {

        renderParameters.validate();

        final BufferedImage bufferedImage = renderParameters.openTargetImage();

        ArgbRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        if (renderFile != null) {
            try {
                Utils.saveImage(bufferedImage,
                                renderFile,
                                renderParameters.isConvertToGray(),
                                renderParameters.getQuality());
            } catch (final Throwable t) {
                LOG.warn("extractPeaks: failed to save " + renderFile.getAbsolutePath(), t);
            }
        }

        final ImagePlus ip = new ImagePlus( "", bufferedImage);

        final ByteProcessor img = ((ColorProcessor)ip.getProcessor()).getChannel( 1, null );
        final ByteProcessor mask = ((ColorProcessor)ip.getProcessor()).getChannel( 4, null );

        return extractPeaksFromImage( img, mask );
    }

    /**
     * Extract Gaussian peaks from specified buffered image.
     *
     * @param  bufferedImage  image to process.
     *
     * @return list of peaks.
     */
    List<DifferenceOfGaussianPeak<FloatType>> extractPeaksFromImage( final ImageProcessor image, final ByteProcessor mask ) {

        final Timer timer = new Timer();
        timer.start();

        if ( ColorProcessor.class.isInstance( image ) )
        	throw new RuntimeException( "DoG needs a single-channel processor, no ColorProcessor" );

        LOG.info("extractPeaksFromImage: entry");

        // Let imagePlus determine correct processor - original use of ColorProcessor resulted in
        // fewer extracted features when bufferedImage was loaded from disk.
        final ImagePlus imagePlus = new ImagePlus("", image);

        final DescriptorParameters clonedParameters = cloneParametersForRenderUseCase(coreDescriptorParameters);

        final int channel = 0;       // rendered result is always single channel, so set channel to 0
        final int timePoint = 0;     // timePoint is always 0 for pair wise matching

        final float[] minMax = new float[] {0, 255};  //TODO: adjust for 16-bit later, use imagePlus.getBitDepth() ?

        // get the peaks
        final List<DifferenceOfGaussianPeak<FloatType>> peakList =
                extractCandidates(imagePlus,
                                  channel,
                                  timePoint,
                                  clonedParameters,
                                  minMax);

        for ( int i = peakList.size() - 1; i >= 0; --i )
        {
        	final int x = peakList.get( i ).getPosition( 0 );
        	final int y = peakList.get( i ).getPosition( 1 );

        	// make sure it's not on or next to a mask
        	if ( mask.get( x, y ) == 0 || mask.get( x + 1, y ) == 0 || mask.get( x - 1, y ) == 0 || mask.get( x, y + 1 ) == 0 || mask.get( x, y - 1 ) == 0 )
        		peakList.remove( i );
        }

        LOG.info("extractPeaksFromImage: exit, extracted " + peakList.size() + " peaks, elapsedTime=" +
                 timer.stop() + "ms");

        return peakList;
    }

    private static DescriptorParameters cloneParametersForRenderUseCase(final DescriptorParameters descriptorParameters) {

        final DescriptorParameters normalizedParameters = new DescriptorParameters();

        normalizedParameters.dimensionality = descriptorParameters.dimensionality;  // rendered result is always 2D
        normalizedParameters.similarOrientation = true; // TODO: should be false for rotated canvases
        normalizedParameters.channel1 = 0;              // rendered result is always single channel
        normalizedParameters.channel2 = 0;              // rendered result is always single channel

        if (descriptorParameters.sigma != null) {
            normalizedParameters.sigma = Arrays.copyOf((descriptorParameters.sigma), descriptorParameters.sigma.length);
        }

        normalizedParameters.sigma1 = descriptorParameters.sigma1;
        normalizedParameters.sigma2 = descriptorParameters.sigma2;
        normalizedParameters.threshold = descriptorParameters.threshold;
        normalizedParameters.localization = descriptorParameters.localization;
        normalizedParameters.lookForMaxima = descriptorParameters.lookForMaxima;  // not relevant now but may be later
        normalizedParameters.lookForMinima = descriptorParameters.lookForMinima;
        normalizedParameters.numNeighbors = descriptorParameters.numNeighbors;
        normalizedParameters.redundancy = descriptorParameters.redundancy;
        normalizedParameters.significance = descriptorParameters.significance;

        if (descriptorParameters.roi1 != null) {
            normalizedParameters.roi1 = (Roi) descriptorParameters.roi1.clone();
        }

        if (descriptorParameters.roi2 != null) {
            normalizedParameters.roi2 = (Roi) descriptorParameters.roi2.clone();
        }

        // set static class defaults for render use case (just in case they are used somewhere)
        DescriptorParameters.minMaxType = 2;
        DescriptorParameters.min = 0;
        DescriptorParameters.max = 255; //TODO: if this is actually used, 16-bit cases will cause trouble

        return normalizedParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasPeakExtractor.class);
}
