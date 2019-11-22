package org.janelia.alignment.match;

import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Point;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.util.Timer;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.parameters.GeometricDescriptorParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ImageProcessorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import plugin.DescriptorParameters;

import static process.Matching.extractCandidates;

/**
 * Extracts peaks from a canvas (specified by render parameters) using {@link process.Matching#extractCandidates}.
 *
 * @author Stephan Preibisch
 */
public class CanvasPeakExtractor
        implements Serializable {

    private final GeometricDescriptorParameters gdParameters;

    /**
     * Sets up everything that is needed to extract the peak list for a canvas.
     *
     * @param  gdParameters              core descriptor parameters for peak extraction.
     */
    public CanvasPeakExtractor(final GeometricDescriptorParameters gdParameters) {

        this.gdParameters = gdParameters;
    }

    /**
     * Extract Gaussian peaks from canvas built from specified render parameters.
     *
     * @param renderParameters parameters for building canvas.
     * @param renderFile       file to persist rendered canvas (for debugging). Specify as null to skip debug
     *                         persistence.
     *
     * @return list of peaks.
     *
     * @throws IllegalArgumentException
     *   if the specified render parameters are invalid.
     * @throws IllegalStateException
     *   if the specified render parameters have not been initialized.
     */
    public List<DifferenceOfGaussianPeak<FloatType>> extractPeaks(final RenderParameters renderParameters,
                                                                  final File renderFile)
            throws IllegalArgumentException, IllegalStateException {

        renderParameters.validate();

        final ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters, ImageProcessorCache.DISABLED_CACHE, renderFile);

        return extractPeaksFromImageAndMask(imageProcessorWithMasks.ip,
                                            imageProcessorWithMasks.mask);
    }

//    /**
//     * Extract Gaussian peaks from specified buffered image.
//     *
//     * @return list of peaks.
//     */
//    List<DifferenceOfGaussianPeak<FloatType>> extractPeaksFromImage(final BufferedImage bufferedImage) {
//
//        final ImagePlus imagePlus = new ImagePlus("", bufferedImage);
//        final ImageProcessor imageProcessor = imagePlus.getProcessor();
//
//        final ImageProcessor pixelProcessor;
//        final ByteProcessor maskProcessor;
//        if (imageProcessor instanceof ColorProcessor) {
//            pixelProcessor = ((ColorProcessor) imageProcessor).getChannel(1, null);
//            maskProcessor = ((ColorProcessor) imageProcessor).getChannel(4, null);
//        } else if ((imageProcessor instanceof ByteProcessor) ||
//                   (imageProcessor instanceof FloatProcessor) ||
//                   (imageProcessor instanceof ShortProcessor)) {
//            pixelProcessor = imageProcessor;
//            maskProcessor = null;
//        } else  {
//            throw new IllegalArgumentException(
//                    "peaks cannot be extracted from " + imageProcessor.getClass().getSimpleName() + " instances");
//        }
//
//        return extractPeaksFromImageAndMask(pixelProcessor, maskProcessor);
//    }

    /**
     * Extract Gaussian peaks from specified image and mask processors.
     *
     * @return list of peaks.
     */
    List<DifferenceOfGaussianPeak<FloatType>> extractPeaksFromImageAndMask(final ImageProcessor image,
                                                                           final ImageProcessor mask) {

        LOG.info("extractPeaksFromImageAndMask: entry");

        final Timer timer = new Timer();
        timer.start();

        // TODO: is any other check needed here?
        if (image instanceof ColorProcessor) {
            throw new IllegalArgumentException("DoG needs a single-channel processor, no ColorProcessor");
        }

        final ImagePlus imagePlus = new ImagePlus("", image);

        final DescriptorParameters descriptorParameters = gdParameters.toDescriptorParameters();

        final int channel = 0;       // rendered result is always single channel, so set channel to 0
        final int timePoint = 0;     // timePoint is always 0 for pair wise matching

        final float[] minMax = new float[]{0, 255};  //TODO: adjust for 16-bit later, use imagePlus.getBitDepth() ?

        // get the peaks
        final List<DifferenceOfGaussianPeak<FloatType>> peakList =
                extractCandidates(imagePlus,
                                  channel,
                                  timePoint,
                                  descriptorParameters,
                                  minMax);

        // if a mask exists, remove any peaks on or next to a masked pixel
        if (mask != null) {

            for (int i = peakList.size() - 1; i >= 0; --i) {
                final DifferenceOfGaussianPeak<FloatType> peak = peakList.get(i);
                if ( ImageProcessorUtil.isNearMaskedOut(peak.getPosition(0),
                                                        peak.getPosition(1),
                                                        1,
                                                        mask) ) {
                    peakList.remove(i);
                }
            }

        }

        LOG.info("extractPeaksFromImageAndMask: exit, extracted " + peakList.size() + " peaks, elapsedTime=" +
                 timer.stop() + "ms");

        return peakList;
    }

    public void filterPeaksByInliers(final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks,
                                     final double peakRenderScale,
                                     final List<Point> inlierPoints,
                                     final double inlierRenderScale) {

        filterPeaksByInliers(gdParameters.fullScaleBlockRadius,
                             canvasPeaks,
                             peakRenderScale,
                             inlierPoints,
                             inlierRenderScale);
    }

    public List<DifferenceOfGaussianPeak<FloatType>> nonMaximalSuppression(final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks,
                                                                           final double peakRenderScale) {

        return nonMaximalSuppression(gdParameters.fullScaleNonMaxSuppressionRadius,
                                     canvasPeaks,
                                     peakRenderScale);
    }

    @SuppressWarnings("WeakerAccess")
    public static void filterPeaksByInliers(final Double fullScaleBlockRadius,
                                            final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks,
                                            final double peakRenderScale,
                                            final List<Point> inlierPoints,
                                            final double inlierRenderScale) {

        if ((fullScaleBlockRadius != null) && (canvasPeaks.size() > 0) && (inlierPoints.size() > 0)) {

            final double scaledBlockRadius = peakRenderScale * fullScaleBlockRadius;

            LOG.info("filterPeaksByInliers: entry, peakRenderScale: {}, inlierRenderScale: {}, scaledBlockRadius: {}",
                     peakRenderScale, inlierRenderScale, scaledBlockRadius);

            final int beforeCount = canvasPeaks.size();

            // make a KDTree from the re-scaled inliers
            final List<RealPoint> list = adjustInliers(peakRenderScale, inlierPoints, inlierRenderScale);

            // make the KDTree
            final KDTree<RealPoint> tree = new KDTree<>(list, list);

            // Nearest neighbor for each point, populate the new list
            final NearestNeighborSearchOnKDTree<RealPoint> nn = new NearestNeighborSearchOnKDTree<>(tree);

            for (int i = canvasPeaks.size() - 1; i >= 0; --i) {
                final DifferenceOfGaussianPeak<FloatType> ip = canvasPeaks.get(i);
                final RealPoint p = new RealPoint(
                        ip.getSubPixelPosition(0),
                        ip.getSubPixelPosition(1));
                nn.search(p);

                // first nearest neighbor is the point itself, we need the second nearest
                final double d = nn.getDistance();

                if (d <= scaledBlockRadius) {
                    canvasPeaks.remove(i);
                }
            }

            LOG.info("filterPeaksByInliers: exit, removed {} peaks, {} peaks remain",
                     (beforeCount - canvasPeaks.size()), canvasPeaks.size());

        }

    }

    @SuppressWarnings("WeakerAccess")
    public static List<DifferenceOfGaussianPeak<FloatType>> nonMaximalSuppression(final Double fullScaleNonMaxSuppressionRadius,
                                                                                  final List<DifferenceOfGaussianPeak<FloatType>> canvasPeaks,
                                                                                  final double peakRenderScale) {

        List<DifferenceOfGaussianPeak<FloatType>> filteredPeaks = canvasPeaks;

        if ((fullScaleNonMaxSuppressionRadius != null) && (canvasPeaks.size() > 0)) {

            final double scaledNonMaxSuppressionRadius = fullScaleNonMaxSuppressionRadius * peakRenderScale;

            LOG.info("nonMaximalSuppression: entry, peakRenderScale: {}, scaledNonMaxSuppressionRadius: {}",
                     peakRenderScale, scaledNonMaxSuppressionRadius);

            // used for querying
            filteredPeaks = new ArrayList<>();

            for (final DifferenceOfGaussianPeak<FloatType> p : canvasPeaks) {
                filteredPeaks.add(p.copy());
            }

            final List<RealPoint> list = new ArrayList<>();

            for (final DifferenceOfGaussianPeak<FloatType> p : canvasPeaks) {
                list.add(new RealPoint(p.getSubPixelPosition(0), p.getSubPixelPosition(1)));
            }

            // make the KDTree
            final KDTree<DifferenceOfGaussianPeak<FloatType>> tree = new KDTree<>(canvasPeaks, list);

            // Nearest neighbor for each point, populate the new list
            final RadiusNeighborSearchOnKDTree<DifferenceOfGaussianPeak<FloatType>>
                    nn = new RadiusNeighborSearchOnKDTree<>(tree);

            for (int i = filteredPeaks.size() - 1; i >= 0; --i) {
                final DifferenceOfGaussianPeak<FloatType> ip = filteredPeaks.get(i);
                final RealPoint p = new RealPoint(
                        ip.getSubPixelPosition(0),
                        ip.getSubPixelPosition(1));
                nn.search(p, scaledNonMaxSuppressionRadius, false);

                // if am I am not the biggest point within the radius remove myself
                boolean isBiggest = true;

                for (int j = 0; j < nn.numNeighbors(); ++j) {
                    if (Math.abs(nn.getSampler(j).get().getValue().get()) > Math.abs(ip.getValue().get())) {
                        isBiggest = false;
                        break;
                    }
                }

                if (!isBiggest) {
                    filteredPeaks.remove(i);
                }
            }

            LOG.info("nonMaximalSuppression: exit, removed {} peaks, {} peaks remain",
                     (canvasPeaks.size() - filteredPeaks.size()), filteredPeaks.size());

        }

        return filteredPeaks;
    }

    @SuppressWarnings("WeakerAccess")
    public static List<RealPoint> adjustInliers(final double peakRenderScale,
                                                final List<Point> inlierPoints,
                                                final double inlierRenderScale) {

        final List<RealPoint> adjustedInlierPoints = new ArrayList<>(inlierPoints.size());

        // TODO: do not ignore world coordinates
        for (final Point p : inlierPoints) {
            final double adjustedX = (p.getL()[0] / inlierRenderScale) * peakRenderScale;
            final double adjustedY = (p.getL()[1] / inlierRenderScale) * peakRenderScale;
            adjustedInlierPoints.add(new RealPoint(adjustedX, adjustedY));
        }

        return adjustedInlierPoints;
    }


    private static final Logger LOG = LoggerFactory.getLogger(CanvasPeakExtractor.class);
}
