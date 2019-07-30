package org.janelia.alignment.filter;

import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;

import java.util.LinkedHashMap;
import java.util.Map;

public class Rank
        implements Filter {

    private double radius;

    /**
     * Should be one of:
     * {@link RankFilters#MEAN}, {@link RankFilters#MIN}, {@link RankFilters#MAX},
     * {@link RankFilters#VARIANCE}, or {@link RankFilters#MEDIAN}
     */
    private int filterType;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("WeakerAccess")
    public Rank() {
        this(4.0, RankFilters.MIN);
    }

    @SuppressWarnings("WeakerAccess")
    public Rank(final double radius,
                final int filterType) {
        this.radius = radius;
        this.filterType = filterType;
    }

    @Override
    public void init(final Map<String, String> params) {
        this.radius = Filter.getDoubleParameter("radius", params);
        this.filterType = Filter.getIntegerParameter("filterType", params);
        if ((this.filterType < RankFilters.MEAN) || (this.filterType > RankFilters.MEDIAN)) {
            throw new IllegalArgumentException("'filterType' parameter must be between " +
                                               RankFilters.MEAN + " and " + RankFilters.MEDIAN + " (inclusive)");
        }
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("radius", String.valueOf(radius));
        map.put("filterType", String.valueOf(filterType));
        return map;
    }

    @Override
    public ImageProcessor process(final ImageProcessor ip,
                                  final double scale) {
        final RankFilters rankFilters = new RankFilters();
        rankFilters.rank(ip,
                         (double) Math.round(radius * scale),
                         filterType);
        return ip;
    }
}
