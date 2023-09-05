package org.janelia.alignment.spec;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Group of bounds that should be processed together (ostensibly to achieve some processing efficiency).
 *
 * @author Eric Trautman
 */
public class BoundsBatch {

    private final List<Bounds> list;

    public BoundsBatch() {
        this.list = new ArrayList<>();
    }

    public void add(final Bounds bounds) {
        list.add(bounds);
    }

    public List<Bounds> getList() {
        return list;
    }

    @Override
    public String toString() {
        return "\"batch\": { \"size\": " + list.size() + ", \"list\": " + list + '}';
    }

    public double getTotalArea() {
        double totalArea = 0.0;
        for (final Bounds bounds : list) {
            totalArea += bounds.getDeltaX() * bounds.getDeltaY() * bounds.getDeltaZ();
        }
        return totalArea;
    }

    public static List<BoundsBatch> batchByAdjacentZThenXY(final Bounds overallBounds,
                                                           final int rowCount,
                                                           final int columnCount,
                                                           final int batchCount) {
        // Goals for regional cross correlation use case:
        // - save full render time by batching z adjacent cells together (allows use of previously rendered cells)
        // - save load time by batching x-y adjacent cells together (allows use of image processor cache)
        // - produce batchCount batches with similar number of cells in each batch (try to balance parallel workload)

        LOG.info("batchByAdjacentZThenXY: entry, overallBounds={}, rowCount={}, columnCount={} batchCount={}",
                 overallBounds, rowCount, columnCount, batchCount);

        final int cellWidth = (int) Math.ceil(overallBounds.getDeltaX() / columnCount);
        final int cellHeight = (int) Math.ceil(overallBounds.getDeltaY() / rowCount);
        final int cellsPerLayer = rowCount * columnCount;
        final int layerCount = (int) Math.ceil(overallBounds.getDeltaZ());
        final int totalCellCount = cellsPerLayer * layerCount;

        int cellsPerBatch = (int) Math.ceil((double) totalCellCount / batchCount);
        final int numberOfBatchesWithAllCells = totalCellCount > batchCount ? totalCellCount % batchCount : batchCount;

        LOG.info("batchByAdjacentZThenXY: totalCellCount={}, cellsPerBatch={}, numberOfBatchesWithAllCells={}",
                 totalCellCount, cellsPerBatch, numberOfBatchesWithAllCells);

        final List<BoundsBatch> boundsBatchList = new ArrayList<>(batchCount);

        int numberOfCellsInCurrentBatch = 0;
        BoundsBatch currentBatch = new BoundsBatch();
        for (int row = 0; row < rowCount; row++) {

            final double minY = row * cellHeight + overallBounds.getMinY();
            final double maxY = minY + cellHeight;

            for (int column = 0; column < columnCount; column++) {

                final double minX = column * cellWidth + overallBounds.getMinX();
                final double maxX = minX + cellWidth;

                for (int minLayer = 0; minLayer < layerCount;) {

                    final int cellsToFill = cellsPerBatch - numberOfCellsInCurrentBatch;
                    final int remainingLayers = layerCount - minLayer;
                    final int maxLayer;
                    if (cellsToFill < remainingLayers) {
                        maxLayer = minLayer + cellsToFill;
                        numberOfCellsInCurrentBatch += cellsToFill;
                    } else {
                        maxLayer = minLayer + remainingLayers;
                        numberOfCellsInCurrentBatch += remainingLayers;
                    }

                    final double minZ = overallBounds.getMinZ() + minLayer;
                    final double maxZ = overallBounds.getMinZ() + maxLayer;
                    currentBatch.add(new Bounds(minX, minY, minZ, maxX, maxY, maxZ));

                    if (numberOfCellsInCurrentBatch >= cellsPerBatch) {
                        // LOG.debug("batch {} has {} cells", boundsBatchList.size(), numberOfCellsInCurrentBatch);
                        boundsBatchList.add(currentBatch);
                        currentBatch = new BoundsBatch();
                        numberOfCellsInCurrentBatch = 0;
                        if (boundsBatchList.size() == numberOfBatchesWithAllCells) {
                            cellsPerBatch -= 1;
                        }
                    }

                    minLayer = maxLayer;
                }

            }
        }

        if (! currentBatch.list.isEmpty()) {
            // LOG.debug("batch {} has {} cells", boundsBatchList.size(), numberOfCellsInCurrentBatch);
            boundsBatchList.add(currentBatch);
        }

        return boundsBatchList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoundsBatch.class);
}
