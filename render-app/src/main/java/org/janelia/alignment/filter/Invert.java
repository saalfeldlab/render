package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

public class Invert
        implements Filter {

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public Invert() {
    }

    @Override
    public void init(final Map<String, String> params) {
    }

    @Override
    public Map<String, String> toParametersMap() {
        return new HashMap<>();
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        // copied from https://imagej.nih.gov/ij/plugins/download/Image_Inverter.java
        final Rectangle r = ip.getRoi();
        final int maxY = r.y + r.height;
        final int maxX = r.x + r.width;
        for (int y = r.y; y < maxY; y++) {
            for (int x = r.x; x < maxX; x++) {
                ip.set(x, y, ~ip.get(x, y));
            }
        }

        return ip;
    }

}
