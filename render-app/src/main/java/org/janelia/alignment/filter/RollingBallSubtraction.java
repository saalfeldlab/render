package org.janelia.alignment.filter;

import ij.plugin.filter.BackgroundSubtracter;
import ij.process.ImageProcessor;

import java.util.Map;

public class RollingBallSubtraction implements Filter {
    protected double radius = 250;

    public RollingBallSubtraction() {
    }

    public RollingBallSubtraction(final double radius) {
        set(radius);
    }

    public final void set(final double radius) {
        this.radius = radius;
    }

    public RollingBallSubtraction(final Map<String, String> params) {
        try {
            set(Double.parseDouble(params.get("radius")));
        } catch (final NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Could not create RollingBallSubtraction filter!", nfe);
        }
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip, final double scale) {
        try {
        	BackgroundSubtracter bgsub = new ij.plugin.filter.BackgroundSubtracter();	
            bgsub.rollingBallBackground(ip,
                    (double) Math.round(radius * scale),false,false,false,true,true);
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return ip;
    }

    @Override
    public boolean equals(final Object o) {
        if (null == o)
            return false;
        if (o.getClass() == RollingBallSubtraction.class) {
            final RollingBallSubtraction c = (RollingBallSubtraction) o;
            return radius == c.radius && radius == c.radius;
        }
        return false;
    }
}
