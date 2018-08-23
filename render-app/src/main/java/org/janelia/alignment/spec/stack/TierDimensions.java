package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulates the dimensions for one tier of hierarchical stacks and
 * provides utilities to split layers of a roughly aligned stack into tiers and cells.
 *
 * @author Eric Trautman
 */
public class TierDimensions implements Serializable {

    public enum LayerSplitMethod {

        /**
         * This method starts at the highest resolution (full scale) tier and divides the tier
         * into cells of a specified size that are centered on the roughly aligned stack.
         * It then divides the preceding tier by doubling the cell size, halving the scale,
         * and offsetting the cells by half the width and height of the current tier cell size.
         * This process is repeated for each preceding tier until there is only one cell in the tier.
         * The offset-by-half approach ensures that there are no overlapping cell bounds between tiers.
         */
        CENTER,

        /**
         * This method is the same as the {@link #CENTER} method except that it takes a
         * specified pixels per cell value instead of an explicit cell width and height.
         * The cell width and height is then derived to match the width:height aspect ratio
         * of the roughly aligned stack.  This often reduces the total number of tiers by one.
         */
        CENTER_ASPECT,

        /**
         * This method roughly doubles cell resolution with each subsequent tier.
         * It attempts to prevent overlapping cell bounds between tiers by choosing a prime number of
         * rows and columns for each tier (e.g. tier 0: 1x1, tier 1: 3x3, tier 2: 7x7 ...).
         */
        PRIME,

        /**
         * This method is similar to the {@link #CENTER} method except it "aligns" the
         * tier 1 upper left corner with the upper left corner of the roughly aligned stack
         * (instead of working from the stack center).
         */
        UPPER_LEFT
    }

    private final int fullScaleCellWidth;
    private final int fullScaleCellHeight;
    private final double scale;
    private final int rows;
    private final int columns;
    private final Bounds fullScaleBounds;

    /**
     * Value constructor.
     *
     * @param  fullScaleCellWidth         full scale (world) width for one cell in this tier's grid.
     * @param  fullScaleCellHeight        full scale (world) height for one cell in this tier's grid.
     * @param  scale                      scale factor for all cells in this tier.
     * @param  rows                       number of rows in this tier's grid.
     * @param  columns                    number of columns in this tier's grid.
     * @param  fullScaleBounds            full scale (world) bounds of this tier's grid.
     */
    private TierDimensions(final int fullScaleCellWidth,
                           final int fullScaleCellHeight,
                           final double scale,
                           final int rows,
                           final int columns,
                           final Bounds fullScaleBounds) {
        this.fullScaleCellWidth = fullScaleCellWidth;
        this.fullScaleCellHeight = fullScaleCellHeight;
        this.scale = scale;
        this.rows = rows;
        this.columns = columns;
        this.fullScaleBounds = fullScaleBounds;
    }

    Bounds getFullScaleBounds() {
        return fullScaleBounds;
    }

    int getFullScaleCellWidth() {
        return fullScaleCellWidth;
    }

    int getFullScaleCellHeight() {
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
     * @param  row     split stack row.
     * @param  column  split stack column.
     *
     * @return full scale (world) bounds for the specified row and column.
     */
    @JsonIgnore
    Bounds getCellBounds(final int row,
                         final int column) {

        final double minX = fullScaleBounds.getMinX() + (column * fullScaleCellWidth);
        final double minY = fullScaleBounds.getMinY() + (row * fullScaleCellHeight);

        return new Bounds(minX, minY, fullScaleBounds.getMinZ(),
                          (minX + fullScaleCellWidth), (minY + fullScaleCellHeight), fullScaleBounds.getMaxZ());
    }

    /**
     * @param  roughTilesStackId  roughly aligned stack being split.
     * @param  tier               index of this tier.
     *
     * @return list of split stacks in this tier (built using these dimensions).
     */
    @JsonIgnore
    public List<HierarchicalStack> getSplitStacks(final StackId roughTilesStackId,
                                                  final int tier) {

        final List<HierarchicalStack> splitStacks = new ArrayList<>();

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                splitStacks.add(new HierarchicalStack(roughTilesStackId,
                                                      tier,
                                                      row,
                                                      column,
                                                      rows,
                                                      columns,
                                                      scale,
                                                      getCellBounds(row, column)));
            }
        }

        return splitStacks;
    }

    /**
     * @param  splitMethod                method for splitting tiers.
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  cellPixelWidth             pixel width for each cell in each (potentially scaled) tier.
     * @param  cellPixelHeight            pixel height for each cell in each (potentially scaled) tier.
     * @param  maxNumberOfTiers           maximum number of tiers to include
     *                                    (null indicates max should be dynamically determined).
     *
     * @return list of dimensions for each tier built using the specified split method.
     *
     * @throws IllegalArgumentException
     *   if an unsupported split method is specified.
     */
    public static List<TierDimensions> buildTierDimensionsList(final LayerSplitMethod splitMethod,
                                                               final Bounds roughStackFullScaleBounds,
                                                               final int cellPixelWidth,
                                                               final int cellPixelHeight,
                                                               final Integer maxNumberOfTiers)
            throws IllegalArgumentException {

        List<TierDimensions> list;
        switch (splitMethod) {

            case CENTER:
                list = TierDimensions.buildCenterTierDimensionsList(roughStackFullScaleBounds,
                                                                    cellPixelWidth,
                                                                    cellPixelHeight);
                break;
            case CENTER_ASPECT:
                list = TierDimensions.buildCenterAspectTierDimensionsList(roughStackFullScaleBounds,
                                                                          (cellPixelHeight * cellPixelWidth));
                break;
            case PRIME:
                final int numberOfTiers = (maxNumberOfTiers == null) ? 4 : maxNumberOfTiers;
                list = TierDimensions.buildPrimeTierDimensionsList(roughStackFullScaleBounds,
                                                                   Math.max(cellPixelWidth, cellPixelHeight),
                                                                   numberOfTiers);
                break;
            case UPPER_LEFT:
                list = TierDimensions.buildUpperLeftTierDimensionsList(roughStackFullScaleBounds,
                                                                       cellPixelWidth,
                                                                       cellPixelHeight);
                break;
            default:
                throw new IllegalArgumentException("split method '" + splitMethod + "' is not supported");
        }

        if ((maxNumberOfTiers != null) && (list.size() > maxNumberOfTiers)) {
            list = list.subList(0, maxNumberOfTiers);
        }

        return list;
    }

    /**
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  cellPixelWidth             pixel width for each cell in each (potentially scaled) tier.
     * @param  cellPixelHeight            pixel height for each cell in each (potentially scaled) tier.
     *
     * @return list of dimensions for each tier built using the {@link LayerSplitMethod#CENTER} method.
     */
    static List<TierDimensions> buildUpperLeftTierDimensionsList(final Bounds roughStackFullScaleBounds,
                                                                 final int cellPixelWidth,
                                                                 final int cellPixelHeight) {

        final int fullScaleWidth = (int) Math.ceil(roughStackFullScaleBounds.getDeltaX());
        final int fullScaleHeight = (int) Math.ceil(roughStackFullScaleBounds.getDeltaY());

        int fullScaleCellWidth = cellPixelWidth;
        int fullScaleCellHeight = cellPixelHeight;
        double scale = 1.0;
        int tierCount = 0;
        int rows;
        int columns;
        do {
            tierCount++;
            fullScaleCellWidth *= 2;
            fullScaleCellHeight *= 2;
            scale /= 2.0;
            rows = (int) Math.ceil(fullScaleHeight / (double) fullScaleCellHeight);
            columns = (int) Math.ceil(fullScaleWidth / (double) fullScaleCellWidth);
        } while (((fullScaleCellWidth < fullScaleWidth) || (fullScaleCellHeight < fullScaleHeight)) &&
                 (rows > 3) &&
                 (columns > 3));

        // "undo" last scaling iteration which exceeded bounds to set up tier 1 values
        fullScaleCellWidth /= 2;
        fullScaleCellHeight /= 2;
        scale *= 2.0;
        rows = (int) Math.ceil(fullScaleHeight / (cellPixelHeight / scale));
        columns = (int) Math.ceil(fullScaleWidth / (cellPixelWidth / scale));

        final List<TierDimensions> list = new ArrayList<>(tierCount);

        // add tier 0 with 1 cell that fits rough stack bounds
        list.add(buildTierZero(roughStackFullScaleBounds, cellPixelWidth, cellPixelHeight));

        if (tierCount > 1) {

            // place tier 1 upper left at rough stack upper left
            int minX = (int) Math.floor(roughStackFullScaleBounds.getMinX());
            int minY = (int) Math.floor(roughStackFullScaleBounds.getMinY());
            Bounds fullScaleBounds = new Bounds((double) minX,
                                                (double) minY,
                                                roughStackFullScaleBounds.getMinZ(),
                                                (double) (minX + (columns * fullScaleCellWidth)),
                                                (double) (minY + (rows * fullScaleCellHeight)),
                                                roughStackFullScaleBounds.getMaxZ());

            list.add(new TierDimensions(fullScaleCellWidth,
                                        fullScaleCellHeight,
                                        scale,
                                        rows,
                                        columns,
                                        fullScaleBounds));

            int insideCenterX = (int) Math.floor(fullScaleBounds.getMinX() + (fullScaleCellWidth * 0.25));
            int insideCenterY = (int) Math.floor(fullScaleBounds.getMinY() + (fullScaleCellHeight * 0.25));
            int cellsFromInside = 1;
            for (int tier = 2; tier <= tierCount; tier++) {
                fullScaleCellWidth /= 2;
                fullScaleCellHeight /= 2;
                scale *= 2.0;

                minX = (int) Math.floor(insideCenterX - (cellsFromInside * fullScaleCellWidth));
                minY = (int) Math.floor(insideCenterY - (cellsFromInside * fullScaleCellHeight));

                rows = (int) Math.ceil((roughStackFullScaleBounds.getMaxY() - minY) / fullScaleCellHeight);
                columns = (int) Math.ceil((roughStackFullScaleBounds.getMaxX() - minX) / fullScaleCellWidth);

                fullScaleBounds = new Bounds((double) minX,
                                             (double) minY,
                                             roughStackFullScaleBounds.getMinZ(),
                                             (double) (minX + (columns * fullScaleCellWidth)),
                                             (double) (minY + (rows * fullScaleCellHeight)),
                                             roughStackFullScaleBounds.getMaxZ());

                list.add(new TierDimensions(fullScaleCellWidth,
                                            fullScaleCellHeight,
                                            scale,
                                            rows,
                                            columns,
                                            fullScaleBounds));

                insideCenterX = (int) Math.floor(insideCenterX + (fullScaleCellWidth * 0.25));
                insideCenterY = (int) Math.floor(insideCenterY + (fullScaleCellHeight * 0.25));
                cellsFromInside = (int) Math.pow(2, (tier - 1));
            }
        }

        LOG.info("buildUpperLeftTierDimensionsList: returning dimensions for {} tiers: {}", list.size(), list);

        return list;
    }

    /**
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  cellPixelWidth             pixel width for each cell in each (potentially scaled) tier.
     * @param  cellPixelHeight            pixel height for each cell in each (potentially scaled) tier.
     *
     * @return list of dimensions for each tier built using the {@link LayerSplitMethod#CENTER} method.
     */
    private static List<TierDimensions> buildCenterTierDimensionsList(final Bounds roughStackFullScaleBounds,
                                                                      final int cellPixelWidth,
                                                                      final int cellPixelHeight) {

        final List<TierDimensions> list = new ArrayList<>();

        TierDimensions currentTier = buildCenterSplitTier(roughStackFullScaleBounds,
                                                          cellPixelWidth,
                                                          cellPixelHeight,
                                                          1.0);
        TierDimensions nextTier = null;

        while (currentTier.getRows() > 1 || currentTier.getColumns() > 1) {

            list.add(currentTier);
            currentTier = getParentDimensions(roughStackFullScaleBounds, currentTier);

            // if current tier has same number of rows or columns as the tier after it,
            // drop it and exit loop
            if ((nextTier != null) &&
                ((currentTier.getRows() == nextTier.getRows()) ||
                 (currentTier.getColumns() == nextTier.getColumns()))) {
                break;
            } else {
                nextTier = currentTier;
            }
        }

        // add tier 0 with 1 cell that fits rough stack bounds
        list.add(buildTierZero(roughStackFullScaleBounds,cellPixelWidth, cellPixelHeight));

        // reverse order so that dimensions for tier 0 are in element 0 ...
        Collections.reverse(list);

        LOG.info("buildCenterAspectTierDimensionsList: returning dimensions for {} tiers: {}", list.size(), list);

        return list;
    }

    /**
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  maxPixelsPerCell           maximum number of pixels per cell (image) in all tiers.
     *
     * @return list of dimensions for each tier built using the {@link LayerSplitMethod#CENTER_ASPECT} method.
     */
    static List<TierDimensions> buildCenterAspectTierDimensionsList(final Bounds roughStackFullScaleBounds,
                                                                    final int maxPixelsPerCell) {

        final double stackAspectRatio = roughStackFullScaleBounds.getDeltaX() / roughStackFullScaleBounds.getDeltaY();
        final double cellHeight = Math.sqrt(maxPixelsPerCell / stackAspectRatio);
        final double cellWidth = stackAspectRatio * cellHeight;

        return buildCenterTierDimensionsList(roughStackFullScaleBounds, (int) cellWidth, (int) cellHeight);
    }

    /**
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  maxPixelsPerDimension      maximum pixel width and height for each cell in all tiers.
     * @param  numberOfTiers              number of tiers to create.
     *
     * @return list of dimensions for each tier built using the {@link LayerSplitMethod#PRIME} method.
     */
    static List<TierDimensions> buildPrimeTierDimensionsList(final Bounds roughStackFullScaleBounds,
                                                             final int maxPixelsPerDimension,
                                                             final int numberOfTiers) {

        final List<TierDimensions> list = new ArrayList<>();

        Bounds parentStackFullScaleBounds = roughStackFullScaleBounds;
        for (int tier = 0; tier < numberOfTiers; tier++) {
            final TierDimensions tierDimensions = buildPrimeSplitTier(parentStackFullScaleBounds,
                                                                      maxPixelsPerDimension,
                                                                      tier);
            list.add(tierDimensions);
            parentStackFullScaleBounds = tierDimensions.getFullScaleBounds();
        }

        LOG.info("buildPrimeTierDimensionsList: returning dimensions for {} tiers", list.size());

        return list;
    }

    /**
     * @param  roughStackFullScaleBounds  bounds for the roughly aligned stack being split.
     * @param  fullScaleCellWidth         full scale pixel width for one cell in this tier's grid.
     * @param  fullScaleCellHeight        full scale pixel height for one cell in this tier's grid.
     * @param  scale                      scale factor for all cells in this tier.
     *
     * @return dimensions for one tier built using the {@link LayerSplitMethod#CENTER} method.
     */
    private static TierDimensions buildCenterSplitTier(final Bounds roughStackFullScaleBounds,
                                                       final int fullScaleCellWidth,
                                                       final int fullScaleCellHeight,
                                                       final double scale) {

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
        final int rows = (topRows * 2) + 1;

        final int leftColumns;
        if (centerCellBounds.getMinX() <= roughStackFullScaleBounds.getMinX()) {
            leftColumns = 0;
        } else {
            final double leftWidth = centerCellBounds.getMinX() - roughStackFullScaleBounds.getMinX();
            leftColumns = (int) Math.ceil(leftWidth / fullScaleCellWidth);
        }
        final int columns = (leftColumns * 2) + 1;

        final int minX = (int) Math.floor(centerCellBounds.getMinX() - (leftColumns * fullScaleCellWidth));
        final int minY = (int) Math.floor(centerCellBounds.getMinY() - (topRows * fullScaleCellHeight));

        final Bounds fullScaleBounds = new Bounds((double) minX,
                                                  (double) minY,
                                                  roughStackFullScaleBounds.getMinZ(),
                                                  (double) (minX + (columns * fullScaleCellWidth)),
                                                  (double) (minY + (rows * fullScaleCellHeight)),
                                                  roughStackFullScaleBounds.getMaxZ());

        return new TierDimensions(fullScaleCellWidth, fullScaleCellHeight, scale, rows, columns, fullScaleBounds);
    }

    /**
     * @param  parentStackFullScaleBounds  bounds for the parent stack being split.
     * @param  maxPixelsPerDimension       maximum pixel width and height for each cell in the tier.
     * @param  tier                        index of the tier to build.
     *
     * @return dimensions for one tier built using the {@link LayerSplitMethod#PRIME} method.
     */
    static TierDimensions buildPrimeSplitTier(final Bounds parentStackFullScaleBounds,
                                              final int maxPixelsPerDimension,
                                              final int tier) {

        final int[] primeCandidates = { 1, 3, 7, 17, 37, 79, 163, 331, 673, 1361, 2729, 5471, 10949, 21911 };
        final int rowsAndColumns;
        if (tier < primeCandidates.length) {
            rowsAndColumns = primeCandidates[tier];
        } else {
            final int extraTiers = tier - primeCandidates.length + 1;
            rowsAndColumns = (primeCandidates[primeCandidates.length - 1] * 2 * extraTiers) + 1;
        }

        final int parentWidth = (int) Math.ceil(parentStackFullScaleBounds.getDeltaX());
        final int parentHeight = (int) Math.ceil(parentStackFullScaleBounds.getDeltaY());
        final int maxDimension = Math.max(parentWidth, parentHeight);
        final int maxDimensionPerCell = (int) Math.ceil((double) maxDimension / rowsAndColumns);

        final int fullScaleCellWidth = (int) Math.ceil((double) parentWidth / rowsAndColumns);
        final int fullScaleCellHeight = (int) Math.ceil((double) parentHeight / rowsAndColumns);
        final double scale = Math.min(1.0, (double) maxPixelsPerDimension / maxDimensionPerCell);

        final int minX = parentStackFullScaleBounds.getMinX().intValue();
        final int minY = parentStackFullScaleBounds.getMinY().intValue();

        final Bounds fullScaleBounds = new Bounds((double) minX,
                                                  (double) minY,
                                                  parentStackFullScaleBounds.getMinZ(),
                                                  (double) (minX + (rowsAndColumns * fullScaleCellWidth)),
                                                  (double) (minY + (rowsAndColumns * fullScaleCellHeight)),
                                                  parentStackFullScaleBounds.getMaxZ());

        final TierDimensions tierDimensions =
                new TierDimensions(fullScaleCellWidth, fullScaleCellHeight, scale,
                                   rowsAndColumns, rowsAndColumns, fullScaleBounds);

        LOG.info("buildPrimeSplitTier: returning tier {} dimensions: {}", tier, tierDimensions);

        return tierDimensions;
    }

    /**
     * @param  roughStackFullScaleBounds  bounds for the parent stack being split.
     * @param  cellPixelWidth             pixel width for each cell in each (potentially scaled) tier.
     * @param  cellPixelHeight            pixel height for each cell in each (potentially scaled) tier.
     *
     * @return tier 0 dimensions with 1 cell that fits rough stack bounds.
     */
    private static TierDimensions buildTierZero(final Bounds roughStackFullScaleBounds,
                                                final int cellPixelWidth,
                                                final int cellPixelHeight) {
        return buildPrimeSplitTier(roughStackFullScaleBounds, Math.max(cellPixelWidth, cellPixelHeight), 0);
    }

    private static TierDimensions getParentDimensions(final Bounds roughStackFullScaleBounds,
                                                      final TierDimensions child) {

        return buildCenterSplitTier(roughStackFullScaleBounds,
                                    (child.fullScaleCellWidth * 2),
                                    (child.fullScaleCellHeight * 2),
                                    (child.scale / 2.0));
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalTierRegions.class);

    public static final JsonUtils.Helper<TierDimensions> JSON_HELPER =
            new JsonUtils.Helper<>(TierDimensions.class);

}
