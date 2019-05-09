package org.janelia.alignment.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Filters z values based upon range and/or an explicit list.
 *
 * @author Eric Trautman
 */
public class ZFilter {

    private final double min;
    private final double max;
    private final Set<Double> explicitValues;

    /**
     * Basic constructor.
     *
     * @param  minZ             minimum value to accept or null if there is no minimum.
     * @param  maxZ             maximum value to accept or null if there is no maximum.
     * @param  explicitZValues  explicit values to accept or null if not applicable.
     */
    public ZFilter(final Double minZ,
                   final Double maxZ,
                   final Collection<Double> explicitZValues) {

        // if range is specified in any way, include everything in range plus explicit z values
        double minValue = (minZ == null) ? -Double.MAX_VALUE : minZ;
        final double maxValue = (maxZ == null) ? Double.MAX_VALUE : maxZ;

        // if range is not specified, exclude everything except explicit z values
        if ((minZ == null) && (maxZ == null) && (explicitZValues != null)) {
            minValue = Double.MAX_VALUE;
        }

        this.min = minValue;
        this.max = maxValue;

        if (explicitZValues != null) {
            this.explicitValues = new HashSet<>(explicitZValues);
        } else {
            this.explicitValues = new HashSet<>();
        }
    }

    /**
     * @param  z  value to check.
     *
     * @return true if the specified value is to be included; otherwise false.
     */
    public boolean accept(final Double z) {
        return explicitValues.contains(z) || ((z >= min) && (z <= max));
    }

}
