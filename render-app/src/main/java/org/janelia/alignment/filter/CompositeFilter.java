package org.janelia.alignment.filter;

import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds a collection of {@link Filter}s to be applied in sequence.
 *
 * @author Michael Innerberger
 */
public class CompositeFilter implements Filter {

    private List<Filter> filters;

    // empty constructor required to create instances from specifications
    @SuppressWarnings("unused")
    public CompositeFilter() {
        this((List<Filter>) null);
    }

    public CompositeFilter(final Filter... filters) {
        this(List.of(filters));
    }

    public CompositeFilter(final List<Filter> filters) {
        this.filters = filters;
    }

    @Override
    public void init(final Map<String, String> params) {
        filters = new ArrayList<>(params.size());
        for (final String serializedFilter : params.values()) {
            final FilterSpec filterSpec = FilterSpec.fromJson(serializedFilter);
            filters.add(filterSpec.buildInstance());
        }
    }

    @Override
    public Map<String, String> toParametersMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < filters.size(); i++) {
            final FilterSpec filterSpec = FilterSpec.forFilter(filters.get(i));
            map.put(String.valueOf(i), filterSpec.toJson());
        }
        return map;
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
        for (final Filter filter : filters) {
            filter.process(ip, scale);
        }
    }
}
