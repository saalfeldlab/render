package org.janelia.alignment.match;

import ij.process.ImageProcessor;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.util.Timer;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.ImageProcessorUtil;
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

    private transient ImageProcessorCache imageProcessorCache;

    /**
     * Sets up everything that is needed to extract the feature list for a canvas.
     *
     * @param  coreSiftParameters  core SIFT parameters for feature extraction.
     * @param  minScale            SIFT minimum scale (minSize * minScale < size < maxSize * maxScale).
     * @param  maxScale            SIFT maximum scale (minSize * minScale < size < maxSize * maxScale).
     */
    public CanvasFeatureExtractor(@Nonnull final FloatArray2DSIFT.Param coreSiftParameters,
                                  final double minScale,
                                  final double maxScale) {

        // clone provided parameters since they get modified during feature extraction
        this.coreSiftParameters = coreSiftParameters.clone();

        this.minScale = minScale;
        this.maxScale = maxScale;
        this.imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;
    }

    public void setImageProcessorCache(final ImageProcessorCache imageProcessorCache) {
        this.imageProcessorCache = imageProcessorCache;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final CanvasFeatureExtractor that = (CanvasFeatureExtractor) o;

        if (Double.compare(that.minScale, minScale) != 0) {
            return false;
        }
        if (Double.compare(that.maxScale, maxScale) != 0) {
            return false;
        }
        return coreSiftParameters.equals(that.coreSiftParameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coreSiftParameters.fdSize,
                            coreSiftParameters.fdBins,
                            coreSiftParameters.maxOctaveSize,
                            coreSiftParameters.minOctaveSize,
                            coreSiftParameters.steps,
                            coreSiftParameters.initialSigma,
                            minScale,
                            maxScale);
    }

    /**
     * Extracted feature list with information about the sources used to produce it.
     */
    public static class FeaturesWithSourceData {

        private final RenderParameters renderParameters;
        private final ImageProcessorWithMasks renderedProcessorWithMasks;
        private final List<Feature> featureList;

        public FeaturesWithSourceData(final RenderParameters renderParameters,
                                      final ImageProcessorWithMasks renderedProcessorWithMasks,
                                      final List<Feature> featureList) {
            this.renderParameters = renderParameters;
            this.renderedProcessorWithMasks = renderedProcessorWithMasks;
            this.featureList = featureList;
        }

        public RenderParameters getRenderParameters() {
            return renderParameters;
        }

        public ImageProcessorWithMasks getRenderedProcessorWithMasks() {
            return renderedProcessorWithMasks;
        }

        public List<Feature> getFeatureList() {
            return featureList;
        }

        public long getKilobytes() {

            final int featureCount = featureList == null ? 0 : featureList.size();

            long kilobyteCount = (long) (featureCount * AVERAGE_KILOBYTES_PER_FEATURE) + 1;

            if (renderedProcessorWithMasks != null) {
                if (renderedProcessorWithMasks.ip != null) {
                    kilobyteCount += ImageProcessorUtil.getKilobytes(renderedProcessorWithMasks.ip);
                }
                if (renderedProcessorWithMasks.mask != null) {
                    kilobyteCount += ImageProcessorUtil.getKilobytes(renderedProcessorWithMasks.mask);
                }
            }

            if (renderParameters != null) {
                kilobyteCount += 1; // hopefully this is a good enough estimate for most cases
            }

            return kilobyteCount;
        }

    }

    /**
     * Extract SIFT features from canvas built from specified render parameters.
     *
     * @param  renderParameters     parameters for building canvas.
     *
     * @return result object that wraps list of extracted features along with source image information.
     *
     * @throws IllegalArgumentException
     *   if the specified render parameters are invalid.
     *
     * @throws IllegalStateException
     *   if the specified render parameters have not been initialized or no features are found.
     */
    public FeaturesWithSourceData extractFeaturesWithSourceData(final RenderParameters renderParameters)
            throws IllegalArgumentException, IllegalStateException {

        renderParameters.validate();

        final ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters,
                                                       imageProcessorCache,
                                                       null);

        final List<Feature> featureList = extractFeaturesFromImageAndMask(imageProcessorWithMasks.ip,
                                                                          imageProcessorWithMasks.mask);

        return new FeaturesWithSourceData(renderParameters, imageProcessorWithMasks, featureList);
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

        final ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters, ImageProcessorCache.DISABLED_CACHE, renderFile);

        return extractFeaturesFromImageAndMask(imageProcessorWithMasks.ip, imageProcessorWithMasks.mask);
    }

    /**
     * Extract SIFT features from specified buffered image.
     *
     * @param  imageProcessor  image to process.
     * @param  maskProcessor   (optional) mask identifying feature locations that should be removed.
     *
     * @return list of extracted features.
     */
    List<Feature> extractFeaturesFromImageAndMask(final ImageProcessor imageProcessor,
                                                  final ImageProcessor maskProcessor) {

        final Timer timer = new Timer();
        timer.start();

        // clone provided parameters since they get modified during feature extraction
        final FloatArray2DSIFT.Param siftParameters = coreSiftParameters.clone();
        final int w = imageProcessor.getWidth();
        final int h = imageProcessor.getHeight();
        final int minSize = Math.min(w, h);
        final int maxSize = Math.max(w, h);
        siftParameters.minOctaveSize = (int) (minScale * minSize - 1.0);
        siftParameters.maxOctaveSize = (int) Math.round(maxScale * maxSize);

        LOG.info("extractFeatures: entry, fdSize={}, steps={}, minScale={}, maxScale={}, minOctaveSize={}, maxOctaveSize={}",
                 siftParameters.fdSize,
                 siftParameters.steps,
                 minScale,
                 maxScale,
                 siftParameters.minOctaveSize,
                 siftParameters.maxOctaveSize);

        final FloatArray2DSIFT sift = new FloatArray2DSIFT(siftParameters);
        final SIFT ijSIFT = new SIFT(sift);

        final List<Feature> featureList = new ArrayList<>();
        ijSIFT.extractFeatures(imageProcessor, featureList);

        if (featureList.size() == 0) {

            final StringBuilder sb = new StringBuilder(256);
            sb.append("no features were extracted");

            if (imageProcessor.getWidth() < siftParameters.minOctaveSize) {
                sb.append(" because montage image width (").append(imageProcessor.getWidth());
                sb.append(") is less than SIFT minOctaveSize (").append(siftParameters.minOctaveSize).append(")");
            } else if (imageProcessor.getHeight() < siftParameters.minOctaveSize) {
                sb.append(" because montage image height (").append(imageProcessor.getHeight());
                sb.append(") is less than SIFT minOctaveSize (").append(siftParameters.minOctaveSize).append(")");
            } else if (imageProcessor.getWidth() > siftParameters.maxOctaveSize) {
                sb.append(" because montage image width (").append(imageProcessor.getWidth());
                sb.append(") is greater than SIFT maxOctaveSize (").append(siftParameters.maxOctaveSize).append(")");
            } else if (imageProcessor.getHeight() > siftParameters.maxOctaveSize) {
                sb.append(" because montage image height (").append(imageProcessor.getHeight());
                sb.append(") is greater than SIFT maxOctaveSize (").append(siftParameters.maxOctaveSize).append(")");
            } else {
                sb.append(", not sure why, montage image width (").append(imageProcessor.getWidth());
                sb.append(") or height (").append(imageProcessor.getHeight());
                sb.append(") may be less than maxKernelSize derived from SIFT steps(");
                sb.append(siftParameters.steps).append(")");
            }

            LOG.warn(sb.toString());
        }

        // if a mask exists, remove any features on a masked pixel
        if (maskProcessor != null) {
            final int totalFeatureCount = featureList.size();
            for (int i = totalFeatureCount - 1; i >= 0; --i) {
                final double[] location = featureList.get(i).location;
                if ( ImageProcessorUtil.isMaskedOut((int) location[0], (int) location[1], maskProcessor) ) {
                    featureList.remove(i);
                }
            }
            if (totalFeatureCount > featureList.size()) {
                LOG.info("extractFeatures: removed {} features found in the masked region",
                         (totalFeatureCount - featureList.size()));
            }
        }

        LOG.info("extractFeatures: exit, extracted " + featureList.size() +
                 " features, elapsedTime=" + timer.stop() + "ms");

        return featureList;
    }

    public static CanvasFeatureExtractor build(final FeatureExtractionParameters featureExtraction) {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = featureExtraction.fdSize;
        siftParameters.steps = featureExtraction.steps;

        return new CanvasFeatureExtractor(siftParameters,
                                          featureExtraction.minScale,
                                          featureExtraction.maxScale);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFeatureExtractor.class);

    /**
     * Average size of a feature.
     * This was derived from a 2K x 2K FAFB00 image and is hopefully good enough for most needs.
     */
    private static final double AVERAGE_KILOBYTES_PER_FEATURE = 0.6; // 600 bytes

}
