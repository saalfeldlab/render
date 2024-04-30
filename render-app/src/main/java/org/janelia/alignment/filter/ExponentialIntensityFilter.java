package org.janelia.alignment.filter;

import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a multiplicative correction (1 + exp(-a * (y - b))) to the y-direction of the given image data.
 * <p>
 * This filter is used to correct an exponential intensity drop towards the upper edge of the images in
 * MultiSEM stacks.
 *
 * @author Michael Innerberger
 */
public class ExponentialIntensityFilter implements Filter {

    private double a;
    private double b;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public ExponentialIntensityFilter() {
        this(0.0, 0.0);
    }

    public ExponentialIntensityFilter(final double a, final double b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.a = Filter.getDoubleParameter("a", params);
        this.b = Filter.getDoubleParameter("b", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("a", String.valueOf(a));
        map.put("b", String.valueOf(b));
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        // find midpoints of pixels in highest-resolution coordinate system
        final int height = ip.getHeight();
        final double[] y = new double[height];
        for (int i = 0; i < height; i++) {
            y[i] = (i + 0.5) / scale;
        }

        // apply correction
        for (int j = 0; j < height; j++) {
            final float correction = (float) (1.0 + Math.exp(-a * (y[j] - b)));
            for (int i = 0; i < ip.getWidth(); i++) {
                ip.setf(i, j, ip.getf(i, j) * correction);
            }
        }
    }

}
