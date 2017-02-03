package org.janelia.alignment.filter;

import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;

import java.util.Map;

public class EqualizeHistogram implements Filter {
    protected double saturatedpixels = 0.0;

    public EqualizeHistogram() {
    }

    public EqualizeHistogram(final double saturatedpixels) {
        set(saturatedpixels);
    }

    public final void set(final double saturatedpixels) {
        this.saturatedpixels = saturatedpixels;
    }

    public EqualizeHistogram(final Map<String, String> params) {
        try {
            set(Double.parseDouble(params.get("saturatedpixels")));
        } catch (final NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Could not create RollingBallSubtraction filter!", nfe);
        }
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip, final double scale) {
        try {
        	ContrastEnhancer eqhist = new ij.plugin.ContrastEnhancer();	
        	eqhist.equalize(ip);
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return ip;
    }

    @Override
    public boolean equals(final Object o) {
        if (null == o)
            return false;
        if (o.getClass() == EqualizeHistogram.class) {
            final EqualizeHistogram c = (EqualizeHistogram) o;
            return saturatedpixels == c.saturatedpixels && saturatedpixels == c.saturatedpixels;
        }
        return false;
    }
}
