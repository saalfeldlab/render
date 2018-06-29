package org.janelia.alignment.util;

import java.awt.Color;
import java.io.Serializable;

import mpicbg.util.ColorStream;

/**
 * Generate a stream of `random' saturated RGB colors with all colors being maximally distinct from each other.
 *
 * This variant of Stephan's implementation (see {@link ColorStream}) maintains a color index
 * within each instance so that multiple instances can be used within one JVM.
 *
 * @author Eric Trautman
 */
public class DistinctColorStream
        extends ColorStream
        implements Serializable {

    private long index;

    public DistinctColorStream() {
        this.index = -1;
    }

    /**
     * Copied from {@link ColorStream#get}.
     */
    public int getNextArgb() {
        ++index;
        double x = goldenRatio * index;
        x -= ( long )x;
        x *= 6.0;
        final int k = ( int )x;
        final int l = k + 1;
        final double u = x - k;
        final double v = 1.0 - u;

        final int r = interpolate( rs, k, l, u, v );
        final int g = interpolate( gs, k, l, u, v );
        final int b = interpolate( bs, k, l, u, v );

        return argb( r, g, b );
    }

    public Color getNextColor() {
        return new Color(getNextArgb());
    }

}
