package org.janelia.alignment.match;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;

/**
 * Bounds information for overlapping tile clusters that can be easily formatted as JSON.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class ClusterOverlapBounds {

    private final Bounds overlapBounds;
    private final List<TileBounds> greenClusterTileBounds;
    private final List<TileBounds> redClusterTileBounds;

    public ClusterOverlapBounds(final Double z,
                                final Collection<TileBounds> greenClusterTileBounds,
                                final Collection<TileBounds> redClusterTileBounds) {

        this.greenClusterTileBounds =
                greenClusterTileBounds.stream()
                        .sorted(Comparator.comparing(TileBounds::getTileId))
                        .collect(Collectors.toList());

        this.redClusterTileBounds =
                redClusterTileBounds.stream()
                        .sorted(Comparator.comparing(TileBounds::getTileId))
                        .collect(Collectors.toList());

        Bounds bounds = new Bounds();
        for (final TileBounds tileBounds : this.greenClusterTileBounds) {
            bounds = bounds.union(tileBounds);
        }
        for (final TileBounds tileBounds : this.redClusterTileBounds) {
            bounds = bounds.union(tileBounds);
        }
        
        this.overlapBounds = new Bounds(bounds.getMinX(), bounds.getMinY(), z,
                                        bounds.getMaxX(), bounds.getMaxY(), z);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<ClusterOverlapBounds> JSON_HELPER =
            new JsonUtils.Helper<>(ClusterOverlapBounds.class);
}
