package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.Map;

import mpicbg.ij.clahe.Flat;

public class CLAHE implements Filter {
    protected int blockRadius = 500, bins = 256;

    protected float slope = 2.5f;

    protected boolean fast = true;

    public CLAHE() {
    }

    public CLAHE(boolean fast, int blockRadius, int bins, float slope) {
        this.fast = fast;
        this.blockRadius = blockRadius;
        this.bins = bins;
        this.slope = slope;
    }

    public CLAHE(Map<String, String> params) {
        try {
            this.fast = Boolean.parseBoolean(params.get("fast"));
            this.blockRadius = Integer.parseInt(params.get("blockradius"));
            this.bins = Integer.parseInt(params.get("bins"));
            this.slope = Float.parseFloat(params.get("slope"));
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Could not create CLAHE filter!", nfe);
        }
    }

    @Override
    public ImageProcessor process(ImageProcessor ip, final double scale) {
        if (fast) {
            Flat.getFastInstance().run(new ImagePlus("", ip),
                    (int) Math.round(blockRadius * scale), bins, slope, null,
                    false);
        } else {
            Flat.getInstance().run(new ImagePlus("", ip),
                    (int) Math.round(blockRadius * scale), bins, slope, null,
                    false);
        }
        return ip;
    }

    @Override
    public boolean equals(final Object o) {
        if (null == o)
            return false;
        if (o.getClass() == CLAHE.class) {
            final CLAHE c = (CLAHE) o;
            return bins == c.bins && blockRadius == c.blockRadius
                    && slope == c.slope && fast == c.fast;
        }
        return false;
    }
}
