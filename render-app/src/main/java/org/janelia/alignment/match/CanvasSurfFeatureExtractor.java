package org.janelia.alignment.match;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.util.Timer;

import org.ddogleg.struct.FastQueue;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import boofcv.abst.feature.detdesc.DetectDescribePoint;
import boofcv.abst.feature.detect.interest.ConfigFastHessian;
import boofcv.alg.descriptor.UtilFeature;
import boofcv.factory.feature.detdesc.FactoryDetectDescribe;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.struct.feature.BrightFeature;
import boofcv.struct.image.ImageGray;
import georegression.struct.point.Point2D_F64;

/**
 * Extracts features from a canvas (specified by render parameters) using SURF.
 *
 * @author Eric Trautman
 */
public class CanvasSurfFeatureExtractor<T extends ImageGray>
        implements Serializable {

    // TODO: verify these are reasonable SURF defaults (they seem to work for FAFB)
    public static final ConfigFastHessian DEFAULT_CONFIG = new ConfigFastHessian(1, 2, 200, 1, 9, 4, 4);

    private final ConfigFastHessian configFastHessian;
    private final boolean fillWithNoise;
    private final Class<T> imageTypeClass;


    /**
     * Sets up everything that is needed to extract the feature list for a canvas.
     *
     * @param  fillWithNoise       indicates whether the rendered canvas image should be filled with
     *                             noise before rendering to improve point match derivation.
     */
    public CanvasSurfFeatureExtractor(final ConfigFastHessian configFastHessian,
                                      final boolean fillWithNoise,
                                      final Class<T> imageTypeClass) {
        this.configFastHessian = configFastHessian;
        this.fillWithNoise = fillWithNoise;
        this.imageTypeClass = imageTypeClass;
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
    public SurfFeatures extractFeatures(final RenderParameters renderParameters,
                                        final File renderFile)
            throws IllegalArgumentException, IllegalStateException {

        renderParameters.validate();

        final BufferedImage bufferedImage = Render.renderWithNoise(renderParameters, fillWithNoise);

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
    public SurfFeatures extractFeaturesFromImage(final BufferedImage bufferedImage) throws IllegalStateException {

        final Timer timer = new Timer();
        timer.start();

        LOG.info("extractFeatures: entry");

        final DetectDescribePoint<T, BrightFeature> featureDetectionDescriptor =
                FactoryDetectDescribe.surfStable(configFastHessian, null, null, imageTypeClass);

        final T image = ConvertBufferedImage.convertFromSingle(bufferedImage, null, imageTypeClass);

        final List<Point2D_F64> points = new ArrayList<>();

        int numberOfFeatures = 0;
        try {
            featureDetectionDescriptor.detect(image);
            numberOfFeatures = featureDetectionDescriptor.getNumberOfFeatures();
        } catch (final Throwable t) {
            LOG.warn("feature detection failed, ignoring failure", t);
        }

        final FastQueue<BrightFeature> featureDescriptors = UtilFeature.createQueue(featureDetectionDescriptor, 100);

        BrightFeature featureDescriptor;
        for (int i = 0; i < numberOfFeatures; i++) {
            points.add(featureDetectionDescriptor.getLocation(i).copy());
            featureDescriptor = featureDescriptors.grow();
            //noinspection unchecked
            featureDescriptor.setTo(featureDetectionDescriptor.getDescription(i));
        }

        if (points.size() == 0) {
            LOG.warn("no features were extracted");
        }

        LOG.info("extractFeatures: exit, extracted " + points.size() +
                 " features, elapsedTime=" + timer.stop() + "ms");

        return new SurfFeatures(featureDescriptors, points);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasSurfFeatureExtractor.class);
}
