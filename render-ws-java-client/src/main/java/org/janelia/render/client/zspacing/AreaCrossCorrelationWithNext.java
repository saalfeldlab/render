package org.janelia.render.client.zspacing;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;

import org.janelia.alignment.spec.Bounds;

/**
 * Cross correlation between each z layer and the next
 * for all existing layers within the bounds minZ to maxZ and
 * within the same 2D area defined by bounds minX, minY to maxX, maxY.
 *
 * @author Eric Trautman
 */
public class AreaCrossCorrelationWithNext
        implements Serializable {

    private final Bounds bounds;
    private final double[] zValues;
    private final double[] correlationWithNext;

    public AreaCrossCorrelationWithNext(final Bounds bounds,
                                        final List<Double> zValues,
                                        final CrossCorrelationData ccData) {
        final int ccLayerCount = ccData.getLayerCount();
        if (ccLayerCount != zValues.size()) {
            throw new IllegalArgumentException("inconsistent layer counts, provided " + zValues.size() +
                                               " z values with correlation data for " + ccLayerCount + " layers");
        }

        this.bounds = bounds;
        this.zValues = zValues.stream().mapToDouble(d -> d).toArray();

        this.correlationWithNext = new double[ccLayerCount];
        for (int pIndex = 0; pIndex < ccLayerCount; pIndex++) {
            // use qIndex=0 for next layer
            this.correlationWithNext[pIndex] = ccData.getCrossCorrelationValue(pIndex, 0);
        }
    }

    public Bounds getBounds() {
        return bounds;
    }

    public double[] getzValues() {
        return zValues;
    }

    public double getCorrelationWithNextForZ(final double pZ) {
        final int zIndex = (int) (pZ - bounds.getMinZ());
        return correlationWithNext[zIndex];
    }

    public static final Comparator<AreaCrossCorrelationWithNext> COMPARE_BY_MIN_YXZ = (thisArea, thatArea) -> {
        int result = thisArea.bounds.getMinY().compareTo(thatArea.bounds.getMinY());
        if (result == 0) {
            result = thisArea.bounds.getMinX().compareTo(thatArea.bounds.getMinX());
            if (result == 0) {
                result = thisArea.bounds.getMinZ().compareTo(thatArea.bounds.getMinZ());
            }
        }
        return result;
    };

}
