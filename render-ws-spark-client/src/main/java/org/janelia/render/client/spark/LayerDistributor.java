package org.janelia.render.client.spark;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.janelia.alignment.spec.SectionData;

/**
 * Distributes a list of sections grouped by layer (z) across a specified number of buckets.
 * Attempts to distribute layers such that no bucket contains a disproportionate number of tiles.
 *
 * @author Eric Trautman
 */
public class LayerDistributor implements Serializable {

    private final int numberOfBuckets;
    private List<Bucket> bucketList;

    public LayerDistributor(final int numberOfBuckets) {
        this.numberOfBuckets = numberOfBuckets;
        this.bucketList = new ArrayList<>();
    }

    public List<List<Double>> distribute(final List<SectionData> sectionDataList) {

        final List<LayerData> layerList = groupSectionsByZ(sectionDataList);
        Collections.sort(layerList); // sort layers by tile count

        bucketList = bucketLayers(layerList);

        final List<List<Double>> distributedZValues = new ArrayList<>(numberOfBuckets);
        distributedZValues.addAll(bucketList.stream().map(Bucket::getOrderedZValues).collect(Collectors.toList()));

        return distributedZValues;
    }

    public long getMinBucketTileCount() {
        return bucketList.size() == 0 ? 0 : bucketList.get(0).getTileCount();
    }

    public long getMaxBucketTileCount() {
        return bucketList.size() == 0 ? 0 : bucketList.get(bucketList.size() - 1).getTileCount();
    }

    @Override
    public String toString() {
        return "{'numberOfBuckets': " + numberOfBuckets + ", 'bucketList': " + bucketList + "}";
    }

    @Nonnull
    private List<LayerData> groupSectionsByZ(final List<SectionData> sectionDataList) {

        final List<LayerData> layerList = new ArrayList<>(sectionDataList.size());

        Collections.sort(sectionDataList, SectionData.Z_COMPARATOR);

        LayerData layerData = null;
        for (final SectionData sectionData : sectionDataList) {
            if ((layerData == null) || (! layerData.getZ().equals(sectionData.getZ()))) {
                layerData = new LayerData(sectionData);
                layerList.add(layerData);
            } else  {
                layerData.addSection(sectionData);
            }
        }

        return layerList;
    }

    private List<Bucket> bucketLayers(final List<LayerData> sortedLayerList) {

        final PriorityQueue<Bucket> bucketQueue = new PriorityQueue<>(numberOfBuckets);

        int layerIndex = sortedLayerList.size() - 1;
        for (int bucketIndex = 0; bucketIndex < numberOfBuckets; bucketIndex++) {
            if (layerIndex >= 0) {
                bucketQueue.add(new Bucket(sortedLayerList.get(layerIndex)));
                layerIndex--;
            } else {
                break;
            }
        }

        Bucket bucket;
        for (; layerIndex >= 0; layerIndex--) {
            bucket = bucketQueue.remove();
            bucket.addLayer(sortedLayerList.get(layerIndex));
            bucketQueue.add(bucket);
        }

        final List<Bucket> sortedBucketList = new ArrayList<>(numberOfBuckets);
        sortedBucketList.addAll(bucketQueue.stream().collect(Collectors.toList()));
        Collections.sort(sortedBucketList);

        return sortedBucketList;
    }

    public static class LayerData implements Comparable<LayerData>, Serializable {

        private final Double z;
        private long tileCount;

        public LayerData(final SectionData sectionData) {
            this.z = sectionData.getZ();
            this.tileCount = sectionData.getTileCount();
        }

        public Double getZ() {
            return z;
        }

        public long getTileCount() {
            return tileCount;
        }

        public void addSection(final SectionData sectionData) {
            if (z.equals(sectionData.getZ())) {
                tileCount += sectionData.getTileCount();
            } else {
                throw new IllegalArgumentException("expected z " + z + " but was " + sectionData.getZ());
            }
        }

        @Override
        public int compareTo(@Nonnull final LayerData that) {
            int rc = Long.compare(this.tileCount, that.tileCount);
            if (rc == 0) {
                rc = Double.compare(this.z, that.z);
            }
            return rc;
        }

        @Override
        public String toString() {
            return "{'z': " + z + ", 'tileCount': " + tileCount + "}";
        }
    }

    public static class Bucket implements Comparable<Bucket>, Serializable {

        private final List<LayerData> layerDataList;
        private long tileCount;

        public Bucket(final LayerData layerData) {
            layerDataList = new ArrayList<>();
            tileCount = 0;
            addLayer(layerData);
        }

        public void addLayer(final LayerData layerData) {
            layerDataList.add(layerData);
            tileCount += layerData.getTileCount();
        }

        public List<Double> getOrderedZValues() {
            final List<Double> orderedZValues = new ArrayList<>(layerDataList.size());
            orderedZValues.addAll(layerDataList.stream().map(LayerData::getZ).sorted().collect(Collectors.toList()));
            return orderedZValues;
        }

        public long getTileCount() {
            return tileCount;
        }

        @Override
        public int compareTo(@Nonnull final Bucket that) {
            return Long.compare(this.tileCount, that.tileCount);
        }

        @Override
        public String toString() {
            return "{'layerDataList': " + layerDataList + ", 'tileCount': " + tileCount + "}";
        }

    }

}
