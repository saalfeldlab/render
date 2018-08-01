package org.janelia.alignment.spec.stack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulates the dimensions for one tier of hierarchical stacks and
 * provides utilities to "carve-up" a roughly aligned stack into tiers and cells.
 *
 * @author Eric Trautman
 */
public class TierDimensions {

    private final int fullScaleCellWidth;
    private final int fullScaleCellHeight;
    private final double scale;
    private final int rows;
    private final int columns;
    private final Bounds fullScaleBounds;

    /**
     * Constructs dimensions for a tier with its center cell centered on the specified roughly aligned stack.
     *
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  fullScaleCellWidth         full scale pixel width for one cell in this tier's grid.
     * @param  fullScaleCellHeight        full scale pixel height for one cell in this tier's grid.
     * @param  scale                      scale factor for all cells in this tier.
     */
    public TierDimensions(final Bounds roughStackFullScaleBounds,
                          final int fullScaleCellWidth,
                          final int fullScaleCellHeight,
                          final double scale) {

        this.fullScaleCellWidth = fullScaleCellWidth;
        this.fullScaleCellHeight = fullScaleCellHeight;
        this.scale = scale;

        final double centerX = roughStackFullScaleBounds.getCenterX();
        final double centerY = roughStackFullScaleBounds.getCenterY();
        final double halfCellWidth = fullScaleCellWidth / 2.0;
        final double halfCellHeight =  fullScaleCellHeight / 2.0;
        final Bounds centerCellBounds = new Bounds(centerX - halfCellWidth, centerY - halfCellHeight,
                                                   centerX + halfCellWidth, centerY + halfCellHeight);

        final int topRows;
        if (centerCellBounds.getMinY() <= roughStackFullScaleBounds.getMinY()) {
            topRows = 0;
        } else {
            final double topHeight = centerCellBounds.getMinY() - roughStackFullScaleBounds.getMinY();
            topRows = (int) Math.ceil(topHeight / fullScaleCellHeight);
        }
        this.rows = (topRows * 2) + 1;

        final int leftColumns;
        if (centerCellBounds.getMinX() <= roughStackFullScaleBounds.getMinX()) {
            leftColumns = 0;
        } else {
            final double leftWidth = centerCellBounds.getMinX() - roughStackFullScaleBounds.getMinX();
            leftColumns = (int) Math.ceil(leftWidth / fullScaleCellWidth);
        }
        this.columns = (leftColumns * 2) + 1;

        final int minX = (int) Math.floor(centerCellBounds.getMinX() - (leftColumns * fullScaleCellWidth));
        final int minY = (int) Math.floor(centerCellBounds.getMinY() - (topRows * fullScaleCellHeight));

        this.fullScaleBounds = new Bounds((double) minX,
                                          (double) minY,
                                          roughStackFullScaleBounds.getMinZ(),
                                          (double) (minX + (this.columns * this.fullScaleCellWidth)),
                                          (double) (minY + (this.rows * this.fullScaleCellHeight)),
                                          roughStackFullScaleBounds.getMaxZ());
    }

    public Bounds getFullScaleBounds() {
        return fullScaleBounds;
    }

    public int getFullScaleCellWidth() {
        return fullScaleCellWidth;
    }

    public int getFullScaleCellHeight() {
        return fullScaleCellHeight;
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return columns;
    }

    public double getScale() {
        return scale;
    }

    public double getMinX() {
        return fullScaleBounds.getMinX();
    }

    public double getMinY() {
        return fullScaleBounds.getMinY();
    }

    @Override
    public String toString() {
        return String.format("{rows:%d,columns:%d,cellWidth:%d,cellHeight:%d,scale:%5.4f,minX:%1.0f,minY:%1.0f}",
                             rows, columns, fullScaleCellWidth, fullScaleCellHeight, scale, getMinX(), getMinY());
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    /**
     * Builds a tier dimensions list where the cells in each tier have a similar aspect ratio to
     * the roughly aligned stack being split.
     *
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  maxPixelsPerCell           maximum number of pixels per cell (image) in all tiers.
     *
     * @return list of dimensions for each tier.
     */
    public static List<TierDimensions> buildTierDimensionsList(final Bounds roughStackFullScaleBounds,
                                                               final int maxPixelsPerCell) {
        final double stackAspectRatio = roughStackFullScaleBounds.getDeltaX() / roughStackFullScaleBounds.getDeltaY();
        final double cellHeight = Math.sqrt(maxPixelsPerCell / stackAspectRatio);
        final double cellWidth = stackAspectRatio * cellHeight;

        return buildTierDimensionsList(roughStackFullScaleBounds, (int) cellWidth, (int) cellHeight);
    }

    /**
     * Builds a tier dimensions list where the cells in each tier have the specified size.
     *
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  cellPixelWidth             pixel width for each cell in each (potentially scaled) tier.
     * @param  cellPixelHeight            pixel height for each cell in each (potentially scaled) tier.
     *
     * @return list of dimensions for each tier.
     */
    public static List<TierDimensions> buildTierDimensionsList(final Bounds roughStackFullScaleBounds,
                                                               final int cellPixelWidth,
                                                               final int cellPixelHeight) {

        final List<TierDimensions> list = new ArrayList<>();

        TierDimensions currentTier = new TierDimensions(roughStackFullScaleBounds,
                                                        cellPixelWidth,
                                                        cellPixelHeight,
                                                        1.0);
        while (currentTier.getRows() > 1 || currentTier.getColumns() > 1) {
            list.add(currentTier);
            currentTier = getParentDimensions(roughStackFullScaleBounds, currentTier);
        }
        list.add(currentTier);

        // reverse order so that dimensions for tier 0 are in element 0 ...
        Collections.reverse(list);

        LOG.debug("buildTierDimensionsList: returning dimensions for {} tiers: {}", list.size(), list);

        return list;
    }

    private static TierDimensions getParentDimensions(final Bounds roughStackFullScaleBounds,
                                                      final TierDimensions child) {

        return new TierDimensions(roughStackFullScaleBounds,
                                  (child.fullScaleCellWidth * 2),
                                  (child.fullScaleCellHeight * 2),
                                  (child.scale / 2.0));
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalTierRegions.class);

    public static final JsonUtils.Helper<TierDimensions> JSON_HELPER =
            new JsonUtils.Helper<>(TierDimensions.class);

}
