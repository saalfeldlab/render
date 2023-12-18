package org.janelia.render.client.newsolver.blockfactories;

import java.awt.Rectangle;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interface for filtering tiles within blocks based upon bounds.
 *
 * @author Eric Trautman
 */
public interface BlockTileBoundsFilter
        extends Serializable {

    /**
     * @return true if a tile with the specified bounds should be included
     *         in the processing of a block with the specified bounds.
     */
    boolean shouldBeIncluded(final Bounds tileBounds,
                             final Bounds blockBounds);

    BlockTileBoundsFilter INCLUDE_ALL = (tileBounds, blockBounds) -> true;

    BlockTileBoundsFilter XY_MIDPOINT = (tileBounds, blockBounds) -> {
        // only keep tiles where midpoint is inside block to reduce overlap
        final Rectangle blockXYBounds = blockBounds.toRectangle();
        return blockXYBounds.contains(tileBounds.getCenterX(), tileBounds.getCenterY());
    };

    BlockTileBoundsFilter XYZ_MIDPOINT = (tileBounds, blockBounds) -> {
        // only keep tiles where midpoint is inside block to reduce overlap
        return BlockTileBoundsFilter.XY_MIDPOINT.shouldBeIncluded(tileBounds, blockBounds)
                && BlockTileBoundsFilter.Z_INSIDE.shouldBeIncluded(tileBounds, blockBounds);
    };

    BlockTileBoundsFilter SCALED_XY = (tileBounds, blockBounds) -> {
        // only keep tiles that touch a shrunk version of the block
        final Bounds scaledBlockBounds = blockBounds.scaled(0.75, 0.75, 1.0);
        final Rectangle scaledXYBounds = scaledBlockBounds.toRectangle();
        return scaledXYBounds.intersects(tileBounds.toRectangle());
    };

    BlockTileBoundsFilter Z_INSIDE = (tileBounds, blockBounds) -> {
        // only keep tiles that are inside block z range
        return (tileBounds.getMinZ() >= blockBounds.getMinZ()) && (tileBounds.getMaxZ() <= blockBounds.getMaxZ());
    };

    BlockTileBoundsFilter SCALED_XY_Z_INSIDE = (tileBounds, blockBounds) -> {
        // scaled in xy, inside in z
        return BlockTileBoundsFilter.SCALED_XY.shouldBeIncluded(tileBounds, blockBounds)
                && BlockTileBoundsFilter.Z_INSIDE.shouldBeIncluded(tileBounds, blockBounds);
    };

    /**
     * @return list of included tile bounds based upon the specified parameters.
     */
    static List<TileBounds> findIncluded(final Collection<TileBounds> allTileBounds,
                                         final Bounds blockBounds,
                                         final BlockTileBoundsFilter filter) {
        return allTileBounds.stream()
                .filter(tb -> filter.shouldBeIncluded(tb, blockBounds))
                .collect(Collectors.toList());
    }

    /**
     * @return set of included tileIds based upon the specified parameters.
     */
    static Set<String> findIncludedAndConvertToTileIdSet(final Collection<TileSpec> allTileSpecs,
                                                         final Bounds blockBounds,
                                                         final BlockTileBoundsFilter filter) {
        final List<TileBounds> allTileBounds = allTileSpecs.stream().map(TileSpec::toTileBounds).collect(Collectors.toList());
        final List<TileBounds> filteredTileBounds = findIncluded(allTileBounds, blockBounds, filter);

        LOG.info("applyFilterAndConvertToTileIdSet: returning IDs for {} out of {} tiles",
                 filteredTileBounds.size(), allTileBounds.size());

        return filteredTileBounds.stream().map(TileBounds::getTileId).collect(Collectors.toSet());
    }

     Logger LOG = LoggerFactory.getLogger(BlockTileBoundsFilter.class);
}
