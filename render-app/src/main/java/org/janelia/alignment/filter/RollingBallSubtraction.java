package org.janelia.alignment.filter;

import ij.plugin.filter.BackgroundSubtracter;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

public class RollingBallSubtraction implements Filter {

    private double radius;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public RollingBallSubtraction() {
        this(250);
    }

    public RollingBallSubtraction(final double radius) {
        this.radius = radius;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.radius = Filter.getDoubleParameter("radius", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("radius", String.valueOf(radius));
        return map;
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        final BackgroundSubtracter backgroundSubtracter = new BackgroundSubtracter();
        backgroundSubtracter.rollingBallBackground(ip,
                                                   (double) Math.round(radius * scale),
                                                   false,
                                                   false,
                                                   false,
                                                   true,
                                                   true);
        return ip;
    }
}
