package org.janelia.alignment.filter;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 8-bit lookup table filter. Applies a hard-coded 256-entry LUT to {@link ByteProcessor} pixels
 * via {@link ImageProcessor#applyTable(int[])}. The LUT is serialized as a comma-separated list
 * of 256 integers (each clipped to [0, 255]) under the {@link Filter#DATA_STRING_NAME} parameter.
 */
public class LutFilter implements Filter {

    private static final int LUT_SIZE = 256;

    private int[] lut;

    @SuppressWarnings("unused")
    public LutFilter() {
        this.lut = identityLut();
    }

    public LutFilter(final int[] lut) {
        if (lut.length != LUT_SIZE) {
            throw new IllegalArgumentException("lut must have exactly " + LUT_SIZE + " entries");
        }
        this.lut = clipped(lut);
    }

    @Override
    public void init(final Map<String, String> params) {
        final String[] values = Filter.getCommaSeparatedStringParameter(DATA_STRING_NAME, params);
        if (values.length != LUT_SIZE) {
            throw new IllegalArgumentException(
                    "expected " + LUT_SIZE + " LUT values but got " + values.length);
        }
        final int[] parsed = new int[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            parsed[i] = Integer.parseInt(values[i].trim());
        }
        this.lut = clipped(parsed);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final StringBuilder sb = new StringBuilder(LUT_SIZE * 4);
        for (int i = 0; i < LUT_SIZE; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(lut[i]);
        }
        final Map<String, String> map = new LinkedHashMap<>();
        map.put(DATA_STRING_NAME, sb.toString());
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        if (ip instanceof ByteProcessor) {
            ip.applyTable(lut);
        } else {
            LOG.warn("process: skipping non-8-bit ImageProcessor of type {}", ip.getClass().getName());
        }
    }

    private static int[] clipped(final int[] source) {
        final int[] out = new int[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            out[i] = Math.max(0, Math.min(255, source[i]));
        }
        return out;
    }

    private static int[] identityLut() {
        final int[] out = new int[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            out[i] = i;
        }
        return out;
    }

    private static final Logger LOG = LoggerFactory.getLogger(LutFilter.class);
}
