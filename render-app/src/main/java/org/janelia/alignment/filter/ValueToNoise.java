package org.janelia.alignment.filter;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class ValueToNoise implements Filter {

    private double value;
    private double min;
    private double max;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public ValueToNoise() {
        this(Double.NaN, 0, 255);
    }

    public ValueToNoise(final double value,
                        final double min,
                        final double max) {
        this.value = value;
        this.min = min;
        this.max = max;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.value = Filter.getDoubleParameter("value", params);
        this.min = Filter.getDoubleParameter("min", params);
        this.max = Filter.getDoubleParameter("max", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("value", String.valueOf(value));
        map.put("min", String.valueOf(min));
        map.put("max", String.valueOf(max));
        return map;
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        try {
            if (FloatProcessor.class.isInstance(ip)) {
                if (Double.isNaN(value))
                    processFloatNaN((FloatProcessor) ip, min, max);
                else
                    processFloat((FloatProcessor) ip, (float) value, min, max);
            } else {
                if (ColorProcessor.class.isInstance(ip))
                    processColor((ColorProcessor) ip, (int) Math.round(value),
                            (int) Math.round(min), (int) Math.round(max));
                else
                    processGray(ip, (int) Math.round(value),
                            (int) Math.round(min), (int) Math.round(max));
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return ip;
    }

    private static void processFloatNaN(final FloatProcessor ip,
                                        final double min,
                                        final double max) {
        final double scale = max - min;
        final Random rnd = new Random();
        final int n = ip.getWidth() * ip.getHeight();
        for (int i = 0; i < n; ++i) {
            final float v = ip.getf(i);
            if (Float.isNaN(v))
                ip.setf(i, (float) (rnd.nextDouble() * scale + min));
        }
    }

    private static void processFloat(final FloatProcessor ip,
                                     final float value,
                                     final double min,
                                     final double max) {
        final double scale = max - min;
        final Random rnd = new Random();
        final int n = ip.getWidth() * ip.getHeight();
        for (int i = 0; i < n; ++i) {
            final float v = ip.getf(i);
            if (v == value)
                ip.setf(i, (float) (rnd.nextDouble() * scale + min));
        }
    }

    private static void processGray(final ImageProcessor ip,
                                    final int value,
                                    final int min,
                                    final int max) {
        final int scale = max - min + 1;
        final Random rnd = new Random();
        final int n = ip.getWidth() * ip.getHeight();
        for (int i = 0; i < n; ++i) {
            final int v = ip.get(i);
            if (v == value)
                ip.set(i, rnd.nextInt(scale) + min);
        }
    }

    private static void processColor(final ColorProcessor ip,
                                     final int value,
                                     final int min,
                                     final int max) {
        final int scale = max - min + 1;
        final Random rnd = new Random();
        final int n = ip.getWidth() * ip.getHeight();
        for (int i = 0; i < n; ++i) {
            final int v = ip.get(i) & 0x00ffffff;
            if (v == value) {
                final int r = rnd.nextInt(scale) + min;
                final int g = rnd.nextInt(scale) + min;
                final int b = rnd.nextInt(scale) + min;

                ip.set(i, (((((0xff << 8) | r) << 8) | g) << 8) | b);
            }
        }
    }

}
