package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

public class NormalizeLocalContrast implements Filter {

    private int blockRadiusX;
    private int blockRadiusY;
    private float meanFactor;
    private boolean center;
    private boolean stretch;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public NormalizeLocalContrast() {
        this(500, 500, 3, true, true);
    }

    public NormalizeLocalContrast(final int blockRadiusX,
                                  final int blockRadiusY,
                                  final float meanFactor,
                                  final boolean center,
                                  final boolean stretch) {
        this.blockRadiusX = blockRadiusX;
        this.blockRadiusY = blockRadiusY;
        this.meanFactor = meanFactor;
        this.center = center;
        this.stretch = stretch;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.blockRadiusX = Filter.getIntegerParameter("blockRadiusX", params);
        this.blockRadiusY = Filter.getIntegerParameter("blockRadiusY", params);
        this.meanFactor = Filter.getFloatParameter("meanFactor", params);
        this.center = Filter.getBooleanParameter("center", params);
        this.stretch = Filter.getBooleanParameter("stretch", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("blockRadiusX", String.valueOf(blockRadiusX));
        map.put("blockRadiusY", String.valueOf(blockRadiusY));
        map.put("meanFactor", String.valueOf(meanFactor));
        map.put("center", String.valueOf(center));
        map.put("stretch", String.valueOf(stretch));
        return map;
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        mpicbg.ij.plugin.NormalizeLocalContrast.run(ip,
                                                    (int) Math.round(blockRadiusX * scale),
                                                    (int) Math.round(blockRadiusY * scale),
                                                    meanFactor,
                                                    center,
                                                    stretch);
        return ip;
    }

}
