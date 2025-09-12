package org.janelia.alignment.multisem;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For a column of MFOVs within a single z layer, this class maps the bounds of each MFOV to its row number.
 * <br/>
 * The static method {@link #assembleColumnData(StackId, double, List)} assembles a list of
 * MultiSemMfovColumn data for a given z layer.
 */
public class MultiSemMfovColumn {

    private final int columnIndex;
    private final List<TileBounds> mfovBoundsList;
    private final List<Integer> rowList;

    private final int minY;
    private final int maxY;
    private final int minDeltaY;
    private final int halfMinDeltaY;

    /**
     * @param  columnIndex     index of the column in the z layer.
     * @param  mfovBoundsList  list of TileBounds representing each MFOV in this column.
     */
    public MultiSemMfovColumn(final int columnIndex,
                              final List<TileBounds> mfovBoundsList) {
        this.columnIndex = columnIndex;
        this.mfovBoundsList = mfovBoundsList;
        this.rowList = new ArrayList<>();

        final TileBounds firstMfovBounds = mfovBoundsList.get(0);
        this.minY = firstMfovBounds.getMinY().intValue();

        final TileBounds lastMfovBounds = mfovBoundsList.get(mfovBoundsList.size() - 1);
        this.maxY = lastMfovBounds.getMaxY().intValue();

        this.minDeltaY = mfovBoundsList.size() > 1 ? findMinimumDeltaY(mfovBoundsList) : firstMfovBounds.getHeight();

        // Save halfMinDeltaY value because the y value for an MFOV in one column is
        // half an MFOV above or below the MFOV adjacent to it in the next column:
        //            m0002
        //      m0000
        //            m0003
        //      m0001
        //            m0004
        this.halfMinDeltaY = this.minDeltaY / 2;
    }

    public int getMinY() {
        return minY;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mfovBoundsList.size(); i++) {
            sb.append("  ").append(mfovBoundsList.get(i));
            if (i < rowList.size()) {
                sb.append(" assigned to row ").append(rowList.get(i));
            }
            if (i < rowList.size() - 1) {
                sb.append("\n");
            }
        }
        return "\nColumn " + columnIndex + ": minY=" + minY + ", maxY=" + maxY +
               ", minDeltaY=" + minDeltaY + ", halfMinDeltaY=" + halfMinDeltaY + "\n" + sb;
    }

    /**
     * @param  baseDataUrl  the base URL for the render web service.
     * @param  stackId      stack identifier.
     * @param  renderScale  scale to apply to each SFOV within each MFOV when the MFOVs images are later rendered.
     *
     * @return a list of TileSpec objects, one for each MFOV in this column.
     *
     * @throws IllegalArgumentException
     *   if there is an error building the TileSpecs.
     */
    public List<TileSpec> buildMfovTileSpecs(final String baseDataUrl,
                                             final StackId stackId,
                                             final double renderScale)
            throws IllegalArgumentException {

        final List<TileSpec> columnTileSpecs = new ArrayList<>();
        for (int i = 0; i < mfovBoundsList.size(); i++) {
            final TileBounds mfovBounds = mfovBoundsList.get(i);
            final int rowIndex = rowList.size() > i ? rowList.get(i) : 0;
            final LayerMFOV layerMfov = new LayerMFOV(mfovBounds.getZ(),
                                                      mfovBounds.getTileId());
            final TileSpec tileSpec = layerMfov.buildTileSpec(baseDataUrl,
                                                              stackId,
                                                              renderScale,
                                                              rowIndex,
                                                              columnIndex);
            columnTileSpecs.add(tileSpec);
        }

        return columnTileSpecs;
    }

    private static int findMinimumDeltaY(final List<TileBounds> mfovBoundsList) {
        Integer previousMinY = null;
        int minimumDeltaY = 0;
        for (final TileBounds tileBounds : mfovBoundsList) {
            final int currentMinY = tileBounds.getMinY().intValue();
            if (previousMinY != null) {
                if (minimumDeltaY == 0) {
                    minimumDeltaY = currentMinY - previousMinY;
                } else {
                    minimumDeltaY = Math.min(minimumDeltaY, currentMinY - previousMinY);
                }
            }
            previousMinY = tileBounds.getMinY().intValue();
        }
        return minimumDeltaY;
    }

    private void assignRowsByMaxIntersection(final int minYForAllColumns)
            throws IllegalStateException {

        // TODO: fix approach since this one produces incorrect result for some cases
        // Problem example:
        //   The first MFOV in column 8 of z 1 in w60_s360_r00_gc (m0049) is assigned to row 16 instead of 15.
        // Ignoring the problem for now since it will not affect any important function (only the tile neighbors view).

        int adjustedMinY = minY;
        for (int y = minY; y > minYForAllColumns; y -= halfMinDeltaY) {
            adjustedMinY = adjustedMinY - halfMinDeltaY;
        }

        for (final TileBounds mfovBounds : mfovBoundsList) {
            final int mfovX = mfovBounds.getX();
            final int mfovWidth = mfovBounds.getWidth();
            int maxIntersectionHeight = 0;
            int bestRow = -1;
            int row = 0;
            for (int y = adjustedMinY; y <= maxY; y += halfMinDeltaY) {

                final Rectangle mfovRectangle = mfovBounds.toRectangle();
                final Rectangle rowRectangle = new Rectangle(mfovX, y, mfovWidth, minDeltaY);

                final Rectangle intersection = mfovRectangle.intersection(rowRectangle);
                if (intersection.height > maxIntersectionHeight) {
                    maxIntersectionHeight = intersection.height;
                    bestRow = row;
                } else if (maxIntersectionHeight > 0) {
                    break; // stop searching for a row once the largest intersection is found
                }

                row++;
            }

            if (bestRow < 0) {
                throw new IllegalStateException("No suitable row found for " + mfovBounds);
            }

            rowList.add(bestRow);
        }

    }

    /**
     * @param  z                          z value for the layer.
     * @param  boundsForEachSfovInZLayer  list of TileBounds for each SFOV in the z layer.
     *
     * @return a list of MultiSemMfovColumn objects,
     *         each representing a column of MFOVs in the z layer (ordered left to right).
     */
    public static List<MultiSemMfovColumn> assembleColumnData(final StackId stackId,
                                                              final double z,
                                                              final List<TileBounds> boundsForEachSfovInZLayer) {

        LOG.info("assembleColumnData: entry, {} z {}", stackId.toDevString(), z);

        final List<MultiSemMfovColumn> mfovColumnList = new ArrayList<>();

        final Map<String, List<TileBounds>> mfovToSfovBounds = boundsForEachSfovInZLayer.stream().collect(
                Collectors.groupingBy(tb -> MultiSemUtilities.getSimpleMfovForTileId(tb.getTileId())));
        final List<String> sortedMfovIds = mfovToSfovBounds.keySet().stream().sorted().collect(Collectors.toList());

        List<TileBounds> mfovBoundsList = new ArrayList<>();
        TileBounds previousMfovBounds = null;

        // MFOVs are named/numbered 0 to n in columnar order (top-to-bottom then left-to-right):
        //            m0002
        //      m0000
        //            m0003
        //      m0001
        //            m0004
        for (final String mfovId : sortedMfovIds) {

            final List<TileBounds> sfovBoundsList = mfovToSfovBounds.get(mfovId);

            Bounds aggregatedSfovBounds = sfovBoundsList.get(0);
            for (final TileBounds sfovBounds : sfovBoundsList) {
                aggregatedSfovBounds = aggregatedSfovBounds.union(sfovBounds);
            }

            final TileBounds mfovBounds =
                    new TileBounds(mfovId,
                                   String.valueOf(z),
                                   z,
                                   aggregatedSfovBounds.getMinX(),
                                   aggregatedSfovBounds.getMinY(),
                                   aggregatedSfovBounds.getMaxX(),
                                   aggregatedSfovBounds.getMaxY());

            if (previousMfovBounds != null) {
                final double deltaX = mfovBounds.getMinX() - previousMfovBounds.getMinX();
                final double halfPreviousMfovWidth = previousMfovBounds.getWidth() / 2.0;
                if (deltaX > halfPreviousMfovWidth) {
                    // add MFOV bounds list as a column in the column list and start a new MFOV bounds list
                    mfovColumnList.add(new MultiSemMfovColumn(mfovColumnList.size(),
                                                              mfovBoundsList));
                    mfovBoundsList = new ArrayList<>();
                }
            }

            mfovBoundsList.add(mfovBounds);
            previousMfovBounds = mfovBounds;
        }

        if (! mfovBoundsList.isEmpty()) {
            mfovColumnList.add(new MultiSemMfovColumn(mfovColumnList.size(),
                                                      mfovBoundsList));
        }

        if (mfovColumnList.size() > 1) {
            final int minYForAllColumns = mfovColumnList.stream()
                    .mapToInt(MultiSemMfovColumn::getMinY)
                    .min()
                    .orElse(0);

            mfovColumnList.forEach(c -> c.assignRowsByMaxIntersection(minYForAllColumns));
        }

        LOG.info("assembleColumnData: exit, {} z {}, returning\n{}", stackId.toDevString(), z, mfovColumnList);

        return mfovColumnList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiSemMfovColumn.class);
}
