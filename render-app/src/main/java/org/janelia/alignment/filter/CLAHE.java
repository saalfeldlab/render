package org.janelia.alignment.filter;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

import mpicbg.ij.clahe.Flat;

public class CLAHE implements Filter {

    private boolean fast;
    private int blockRadius;
    private int bins;
    private float slope;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public CLAHE() {
        this(true, 500, 256, 2.5f);
    }

    public CLAHE(final boolean fast,
                 final int blockRadius,
                 final int bins,
                 final float slope) {
        this.fast = fast;
        this.blockRadius = blockRadius;
        this.bins = bins;
        this.slope = slope;
    }

    public int getBlockRadius() {
        return blockRadius;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.fast = Filter.getBooleanParameter("fast", params);
        this.blockRadius = Filter.getIntegerParameter("blockRadius", params);
        this.bins = Filter.getIntegerParameter("bins", params);
        this.slope = Filter.getFloatParameter("slope", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("fast", String.valueOf(fast));
        map.put("blockRadius", String.valueOf(blockRadius));
        map.put("bins", String.valueOf(bins));
        map.put("slope", String.valueOf(slope));
        return map;
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        if (fast) {
            Flat.getFastInstance()
                    .run(new ImagePlus("", ip),
                         (int) Math.round(blockRadius * scale), bins, slope, null,
                         false);
        } else {
            Flat.getInstance()
                    .run(new ImagePlus("", ip),
                         (int) Math.round(blockRadius * scale), bins, slope, null,
                         false);
        }
        return ip;
    }
}
