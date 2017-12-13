package org.janelia.render.client.spark.betterbox;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.Partitioner;
import org.janelia.alignment.betterbox.BoxData;
import org.janelia.alignment.betterbox.BoxDataPyramidForLayer;

/**
 * <p>
 *   Spark partitioner for distributing renderable boxes across a cluster.
 *   It encapsulates the light weight summarized results of a more complex
 *   distributed process (see {@link BoxClient#partitionBoxes} used to
 *   find and organize/sort all boxes for large rendering jobs.
 *</p>
 *
 * The partitioner assumes:
 * <ol>
 *     <li>
 *         boxes have already been separated into lists grouped by layer (z) and mipmap level,
 *     </li>
 *     <li>
 *         boxes within each of those lists have been sorted by parent location, and
 *     </li>
 *     <li>
 *         each box 'knows' its position with its list (the box's layer level index) --
 *         see {@link BoxDataPyramidForLayer#addParentBoxesForLevel}.
 *     </li>
 * </ol>
 *
 * @author Eric Trautman
 */
public class BoxPartitioner extends Partitioner {

    private final int numPartitions;
    private final List<LevelPartitioner> levelPartitionerList;

    public BoxPartitioner(final int numPartitions,
                          final Map<Double, List<Integer>> zToLevelBoxCountsMap) {

        this.numPartitions = numPartitions;

        final List<Double> sortedZValues = new ArrayList<>(zToLevelBoxCountsMap.keySet());
        Collections.sort(sortedZValues);

        int maxLevel = 0;
        for (final Double z : sortedZValues) {
            maxLevel = Math.max(zToLevelBoxCountsMap.get(z).size(), maxLevel);
        }

        this.levelPartitionerList = new ArrayList<>();
        LevelPartitioner levelPartitioner = new LevelPartitioner(zToLevelBoxCountsMap, 0, sortedZValues);

        for (int level = 1; level <= maxLevel; level++) {
            this.levelPartitionerList.add(levelPartitioner);
            levelPartitioner = new LevelPartitioner(zToLevelBoxCountsMap, level, sortedZValues);
        }

    }

    @Override
    public int numPartitions() {
        return numPartitions;
    }

    @Override
    public int getPartition(final Object key) {
        int partition = 0;
        if (key instanceof BoxData) {
            final BoxData boxData = (BoxData) key;
            final LevelPartitioner levelPartitioner = levelPartitionerList.get(boxData.getLevel());
            partition = levelPartitioner.getPartition(boxData);
        }
        return partition;
    }

    @Override
    public String toString() {
        return "BoxPartitioner{numPartitions=" + numPartitions +
               ", levelPartitionerList=" + levelPartitionerList +
               '}';
    }

    /**
     * Helper class that encapsulates partitioning logic for one mipmap level.
     */
    private class LevelPartitioner implements Serializable {

        private final int level;
        private final long boxCount;
        private final long boxesPerPartition;
        private final Map<Double, Long> zToStartIndex;

        public LevelPartitioner(final Map<Double, List<Integer>> zToLevelBoxCountsMap,
                                final int level,
                                final List<Double> sortedZValues) {

            this.level = level;
            this.zToStartIndex = new HashMap<>();
            long boxCount = 0;
            List<Integer> levelBoxCounts;
            for (final Double z : sortedZValues) {
                levelBoxCounts = zToLevelBoxCountsMap.get(z);
                if (level < levelBoxCounts.size()) {
                    zToStartIndex.put(z, boxCount);
                    boxCount += levelBoxCounts.get(level);
                }
            }

            this.boxCount = boxCount;
            this.boxesPerPartition = (int) Math.ceil((double) boxCount / numPartitions);
        }

        public int getPartition(final BoxData boxData)
                throws IllegalStateException {

            final int layerLevelIndex = boxData.getLayerLevelIndex();
            final long overallIndex = zToStartIndex.get(boxData.getZ()) + layerLevelIndex;

            return (int) (((double) overallIndex / boxCount) * numPartitions);
        }

        @Override
        public String toString() {
            return "{level=" + level +
                   ", boxCount=" + boxCount +
                   ", boxesPerPartition=" + boxesPerPartition +
                   ", zToStartIndex=" + zToStartIndex +
                   '}';
        }

    }

}
