package org.janelia.alignment.match;

import java.util.List;

import org.ddogleg.struct.FastQueue;

import boofcv.struct.feature.BrightFeature;
import georegression.struct.point.Point2D_F64;

/**
 * Extracted SURF features for an image (canvas).
 *
 * NOTE: This object is not serializable because {@link BrightFeature} is not serializable.
 *       This is okay for now since SURF features are not currently serialized.
 *
 * @author Eric Trautman
 */
public class SurfFeatures {

    private final List<Point2D_F64> points;
    private final FastQueue<BrightFeature> featureDescriptors;

    public SurfFeatures(final FastQueue<BrightFeature> featureDescriptors,
                        final List<Point2D_F64> points) {
        this.featureDescriptors = featureDescriptors;
        this.points = points;
    }

    public List<Point2D_F64> getPoints() {
        return points;
    }

    public FastQueue<BrightFeature> getFeatureDescriptors() {
        return featureDescriptors;
    }

    public int size() {
        return points.size();
    }
}
