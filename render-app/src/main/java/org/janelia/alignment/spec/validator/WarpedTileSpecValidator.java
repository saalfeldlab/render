package org.janelia.alignment.spec.validator;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;

import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tile spec validator instance for tiles with warp (TPS) transformations.
 *
 * @author Eric Trautman
 */
public class WarpedTileSpecValidator
        implements TileSpecValidator {

    private final boolean runCoreValidation;
    private double maxDeltaThreshold;
    private double warnDeltaThreshold;
    private int samplesPerDimension;

    /**
     * No-arg constructor that applies default parameters is required for
     * client instantiation via reflection.
     */
    @SuppressWarnings("unused")
    public WarpedTileSpecValidator() {
        this (true);
    }

    WarpedTileSpecValidator(final boolean runCoreValidation) {
        this (runCoreValidation, 1000.0, 600.0, 64);
    }

    /**
     * Value constructor.
     *
     * @param  runCoreValidation    indicates whether core tile validation (e.g. image and mask existence) should be
     *                              performed - should only be false for test cases.
     *
     * @param  maxDeltaThreshold    tiles with a warp delta greater than this value will trigger a validation exception.
     *
     * @param  warnDeltaThreshold   tiles with a warp delta greater than this value will not trigger a validation
     *                              exception, but will get logged as a warning.
     *
     * @param  samplesPerDimension  the number of sample points in each dimension to include in warp delta evaluation.
     */
    private WarpedTileSpecValidator(final boolean runCoreValidation,
                                    final double maxDeltaThreshold,
                                    final double warnDeltaThreshold,
                                    final int samplesPerDimension) {
        this.runCoreValidation = runCoreValidation;
        this.maxDeltaThreshold = maxDeltaThreshold;
        this.warnDeltaThreshold = warnDeltaThreshold;
        this.samplesPerDimension = samplesPerDimension;
    }

    @Override
    public String toString() {
        return "{ 'class': \"" + getClass() + "\", \"data\": \"" +
               toDataString() + "\" }";
    }

    @Override
    public void init(final String dataString)
            throws IllegalArgumentException {

        final String[] names = { "maxDeltaThreshold:", "warnDeltaThreshold:", "samplesPerDimension:" };
        final double[] values = new double[] { maxDeltaThreshold, warnDeltaThreshold, samplesPerDimension };

        TileSpecValidator.parseDataString(dataString, names, values);

        maxDeltaThreshold = values[0];
        warnDeltaThreshold = values[1];
        samplesPerDimension = (int) values[2];
    }

    @Override
    public String toDataString() {
        return "maxDeltaThreshold:" + maxDeltaThreshold + ", samplesPerDimensionsamplesPerDimension";
    }

    /**
     * @param  tileSpec  specification to validate.
     *
     * @throws IllegalArgumentException
     *   if the specification is invalid.
     */
    @Override
    public void validate(final TileSpec tileSpec)
            throws IllegalArgumentException {

        if (runCoreValidation) {

            try {
                tileSpec.validate();
            } catch (final Throwable t) {
                throw new IllegalArgumentException("core validation failed for tileId '" + tileSpec.getTileId() +
                                                   "', cause: " + t.getMessage(), t);
            }

            if (tileSpec.getZ() == null) {
                throw new IllegalArgumentException("z value is missing for tileId '" + tileSpec.getTileId() + "'");
            }

        }

        final CoordinateTransformList<CoordinateTransform> warpTransformList = tileSpec.getTransformList();
        final double scaleX = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
        final double scaleY = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

        final Model rigidModel = sampleRigidModel(tileSpec.getTileId(),
                                                  warpTransformList,
                                                  scaleX,
                                                  scaleY,
                                                  samplesPerDimension);

        final double maxDelta = calculateMaxDelta(warpTransformList,
                                                  rigidModel,
                                                  scaleX,
                                                  scaleY,
                                                  samplesPerDimension);

        if (maxDelta > maxDeltaThreshold) {
            throw new IllegalArgumentException(
                    "invalid max warp delta of " + maxDelta + " derived for tileId '" + tileSpec.getTileId() +
                    "' with transforms " +
                    tileSpec.getTransforms().toJson().replace('\n', ' '));
        } else if (maxDelta > warnDeltaThreshold) {
            LOG.warn("validate: tile {} has max warp delta of {}", tileSpec.getTileId(), maxDelta);
        }

    }

    // stolen from https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/visualize-ct-difference.bsh#L90-L106
    public static RigidModel2D sampleRigidModel(final String tileId,
                                                final CoordinateTransform ct,
                                                final double scaleX,
                                                final double scaleY,
                                                final int samplesPerDimension) {

        final RigidModel2D model = new RigidModel2D();
        final List<PointMatch> matches = new ArrayList<>();

        try {

            for (int y = 0; y < samplesPerDimension; ++y) {
                final double ys = scaleY * y;
                for (int x = 0; x < samplesPerDimension; ++x) {
                    final double xs = scaleX * x;
                    final Point p = new Point(new double[]{xs, ys});
                    p.apply(ct);
                    matches.add(new PointMatch(p, p));
                }
            }

            model.fit(matches);

        } catch (final Throwable t) {
            throw new IllegalArgumentException("rigid model derivation failed for tileId '" + tileId +
                                               "', cause: " + t.getMessage(), t);
        }

        return model;
    }

    // adapted from https://github.com/axtimwalde/fiji-scripts/blob/master/TrakEM2/visualize-ct-difference.bsh#L56-L72
    private static double calculateMaxDelta(final CoordinateTransform ct1,
                                            final CoordinateTransform ct2,
                                            final double scaleX,
                                            final double scaleY,
                                            final int samplesPerDimension) {

        double maxDelta = 0.0;

        for (int y = 0; y < samplesPerDimension; ++y) {
            for (int x = 0; x < samplesPerDimension; ++x) {
                final double[] l1 = new double[]{x * scaleX, y * scaleY};
                final double[] l2 = new double[]{x * scaleX, y * scaleY};
                ct1.applyInPlace(l1);
                ct2.applyInPlace(l2);
                final double dx = l1[0] - l2[0];
                final double dy = l1[1] - l2[1];
                final double d = Math.sqrt(dx * dx + dy * dy);
                maxDelta = Math.max(maxDelta, d);
            }
        }

        return maxDelta;
    }

    private static final Logger LOG = LoggerFactory.getLogger(WarpedTileSpecValidator.class);
}
