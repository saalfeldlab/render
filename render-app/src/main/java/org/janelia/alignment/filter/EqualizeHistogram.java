package org.janelia.alignment.filter;

import ij.plugin.ContrastEnhancer;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

public class EqualizeHistogram implements Filter {

    private double saturatedPixels;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public EqualizeHistogram() {
        this(0.0);
    }

    public EqualizeHistogram(final double saturatedPixels) {
        this.saturatedPixels = saturatedPixels;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.saturatedPixels = Filter.getDoubleParameter("saturatedPixels", params);
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("saturatedPixels", String.valueOf(saturatedPixels));
        return map;
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        final ContrastEnhancer contrastEnhancer = new ContrastEnhancer();
        contrastEnhancer.equalize(ip);
        return ip;
    }

}
