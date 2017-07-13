package org.janelia.alignment.match;

import ij.ImagePlus;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.util.Timer;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts features from a canvas (specified by render parameters) using SIFT.
 * Core logic stolen from Stephan Saalfeld <saalfelds@janelia.hhmi.org>.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureExtractor implements Serializable {

    private final FloatArray2DSIFT.Param coreSiftParameters;
    private final double minScale;
    private final double maxScale;
    private final boolean fillWithNoise;

    /**
     * Sets up everything that is needed to extract the feature list for a canvas.
     *
     * @param  coreSiftParameters  core SIFT parameters for feature extraction.
     * @param  minScale            SIFT minimum scale (minSize * minScale < size < maxSize * maxScale).
     * @param  maxScale            SIFT maximum scale (minSize * minScale < size < maxSize * maxScale).
     * @param  fillWithNoise       indicates whether the rendered canvas image should be filled with
     *                             noise before rendering to improve point match derivation.
     */
    public CanvasFeatureExtractor(final FloatArray2DSIFT.Param coreSiftParameters,
                                  final double minScale,
                                  final double maxScale,
                                  final boolean fillWithNoise) {

        // clone provided parameters since they get modified during feature extraction
        this.coreSiftParameters = coreSiftParameters.clone();

        this.minScale = minScale;
        this.maxScale = maxScale;
        this.fillWithNoise = fillWithNoise;
    }

    /**
     * Extract SIFT features from canvas built from specified render parameters.
     *
     * @param  renderParameters  parameters for building canvas.
     * @param  renderFile        file to persist rendered canvas (for debugging).
     *                           Specify as null to skip debug persistence.
     *
     * @return list of extracted features.
     *
     * @throws IllegalArgumentException
     *   if the specified render parameters are invalid.
     *
     * @throws IllegalStateException
     *   if the specified render parameters have not been initialized or no features are found.
     */
    public List<Feature> extractFeatures(final RenderParameters renderParameters,
                                         final File renderFile)
            throws IllegalArgumentException, IllegalStateException {

        renderParameters.validate();

        final BufferedImage bufferedImage = ArgbRenderer.renderWithNoise(renderParameters, fillWithNoise);

        if (renderFile != null) {
            try {
                Utils.saveImage(bufferedImage,
                                renderFile,
                                renderParameters.isConvertToGray(),
                                renderParameters.getQuality());
            } catch (final Throwable t) {
                LOG.warn("extractFeatures: failed to save " + renderFile.getAbsolutePath(), t);
            }
        }

        return extractFeaturesFromImage(bufferedImage);
    }

    /**
     * Extract SIFT features from specified buffered image.
     *
     * @param  bufferedImage  image to process.
     *
     * @return list of extracted features.
     *
     * @throws IllegalStateException
     *   if no features are found.
     */
    public List<Feature> extractFeaturesFromImage(final BufferedImage bufferedImage) throws IllegalStateException {

        final Timer timer = new Timer();
        timer.start();

        // clone provided parameters since they get modified during feature extraction
        final FloatArray2DSIFT.Param siftParameters = coreSiftParameters.clone();
        final int w = bufferedImage.getWidth();
        final int h = bufferedImage.getHeight();
        final int minSize = w < h ? w : h;
        final int maxSize = w > h ? w : h;
        siftParameters.minOctaveSize = (int) (minScale * minSize - 1.0);
        siftParameters.maxOctaveSize = (int) Math.round(maxScale * maxSize);

        LOG.info("extractFeatures: entry, fdSize={}, steps={}, minScale={}, maxScale={}, minOctaveSize={}, maxOctaveSize={}",
                 siftParameters.fdSize,
                 siftParameters.steps,
                 minScale,
                 maxScale,
                 siftParameters.minOctaveSize,
                 siftParameters.maxOctaveSize);

        // Let imagePlus determine correct processor - original use of ColorProcessor resulted in
        // fewer extracted features when bufferedImage was loaded from disk.
        final ImagePlus imagePlus = new ImagePlus("", bufferedImage);

        final FloatArray2DSIFT sift = new FloatArray2DSIFT(siftParameters);
        final SIFT ijSIFT = new SIFT(sift);

        final List<Feature> featureList = new ArrayList<>();
        ijSIFT.extractFeatures(imagePlus.getProcessor(), featureList);

        if (featureList.size() == 0) {

            final StringBuilder sb = new StringBuilder(256);
            sb.append("no features were extracted");

            if (bufferedImage.getWidth() < siftParameters.minOctaveSize) {
                sb.append(" because montage image width (").append(bufferedImage.getWidth());
                sb.append(") is less than SIFT minOctaveSize (").append(siftParameters.minOctaveSize).append(")");
            } else if (bufferedImage.getHeight() < siftParameters.minOctaveSize) {
                sb.append(" because montage image height (").append(bufferedImage.getHeight());
                sb.append(") is less than SIFT minOctaveSize (").append(siftParameters.minOctaveSize).append(")");
            } else if (bufferedImage.getWidth() > siftParameters.maxOctaveSize) {
                sb.append(" because montage image width (").append(bufferedImage.getWidth());
                sb.append(") is greater than SIFT maxOctaveSize (").append(siftParameters.maxOctaveSize).append(")");
            } else if (bufferedImage.getHeight() > siftParameters.maxOctaveSize) {
                sb.append(" because montage image height (").append(bufferedImage.getHeight());
                sb.append(") is greater than SIFT maxOctaveSize (").append(siftParameters.maxOctaveSize).append(")");
            } else {
                sb.append(", not sure why, montage image width (").append(bufferedImage.getWidth());
                sb.append(") or height (").append(bufferedImage.getHeight());
                sb.append(") may be less than maxKernelSize derived from SIFT steps(");
                sb.append(siftParameters.steps).append(")");
            }

            throw new IllegalStateException(sb.toString());
        }

        LOG.info("extractFeatures: exit, extracted " + featureList.size() +
                 " features, elapsedTime=" + timer.stop() + "ms");

        return featureList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureExtractor.class);
}
