package org.janelia.alignment.spec;

import java.util.Comparator;

/**
 * Utilities for working with TileSpec Comparator instances.
 */
public class TileSpecComparatorUtil {

    /**
     * Enumeration of supported tileId orders.
     */
    public enum Order {
        ASCENDING_TILE_ID_ORDER(  ASCENDING_TILE_ID_COMPARATOR  ),
        DESCENDING_TILE_ID_ORDER( DESCENDING_TILE_ID_COMPARATOR ),
        CLUSTER_GROUP_ID_ORDER(   CLUSTER_GROUP_ID_COMPARATOR   );

        private final Comparator<TileSpec> comparator;

        Order(final Comparator<TileSpec> comparator) {
            this.comparator = comparator;
        }

        public Comparator<TileSpec> getComparator() {
            return comparator;
        }
    }

    /**
     * Order tile specs by tileId ascending.
     */
    public static final Comparator<TileSpec> ASCENDING_TILE_ID_COMPARATOR = Comparator.comparing(TileSpec::getTileId);

    /**
     * Order tile specs by tileId descending.
     */
    public static final Comparator<TileSpec> DESCENDING_TILE_ID_COMPARATOR = (o1, o2) ->
            -1 * ASCENDING_TILE_ID_COMPARATOR.compare(o1, o2);

    /**
     * Order tile specs by groupId descending and tileId ascending.
     * Specs with groupIds are ordered before specs without groupIds.
     * <br/>
     * Cluster groupIds are expected to be in reverse order by size with
     * the largest cluster having the least lexical groupId.
     * <br/>
     * This supports rendering smaller clusters before larger clusters,
     * ensuring that larger clusters are "on top" in cases where clusters intersect.
     */
    public static final Comparator<TileSpec> CLUSTER_GROUP_ID_COMPARATOR = (o1, o2) -> {
        int result = 0;
        if (o1.getGroupId() == null) {
            if (o2.getGroupId() != null) {
                result = 1;
            }
        } else if (o2.getGroupId() == null) {
            result = -1;
        } else {
            result = o2.getGroupId().compareTo(o1.getGroupId());
        }
        if (result == 0) {
            result = o1.getTileId().compareTo(o2.getTileId());
        }
        return result;
    };

}
