package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parameters for specifying relative distances of z-layers.
 *
 * @author Eric Trautman
 */
public class ZDistanceParameters implements Serializable {

    @Parameter(
            names = "--zDistance",
            description = "Comma separated list of all relative distances of z-layers for which to apply correction. " +
                          "The current z-layer has relative distance 0 and is always corrected. " +
                          "(Omit this parameter to only correct in 2D)")
    private List<Integer> simpleZDistance = new ArrayList<>();

    @Parameter(
            names = "--zDistanceJson",
            description = "Path of JSON file containing array of more complex zDistance specifications.  " +
                          "Specifying this parameter will override --zDistance value.")
    private String zDistanceJson;

    private List<DistanceListForRegion> normalizedDistances;

    public ZDistanceParameters() {
    }

    public ZDistanceParameters(final List<DistanceListForRegion> listOfDistanceLists) {
        this.normalizedDistances = new ArrayList<>();
        for (final DistanceListForRegion sourceList : listOfDistanceLists) {
            this.normalizedDistances.add(new DistanceListForRegion(sourceList.distanceList,
                                                                   sourceList.bounds));
        }
    }

    /**
     * @return normalizedDistances (for testing)
     */
    protected List<DistanceListForRegion> getNormalizedDistances() {
        return normalizedDistances;
    }

    /**
     * Defaults/sets up internal state based upon the external command line parameters.
     * This must be called after the command line parameters are parsed but before other usage of them.
     *
     * @throws IllegalArgumentException
     *   if any of the parameters are invalid.
     */
    public void initDefaultValues()
            throws IllegalArgumentException {
        if (this.zDistanceJson == null) {
            this.normalizedDistances = Collections.singletonList(new DistanceListForRegion(this.simpleZDistance));
        } else {
            try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(this.zDistanceJson)) {
                this.normalizedDistances = DistanceListForRegion.fromJsonArray(reader);
                // make sure JSON data is normalized since normal constructor is bypassed
                this.normalizedDistances.forEach(zd -> zd.normalizeDistanceList(zd.distanceList));
            } catch (final IOException ioe) {
                throw new IllegalArgumentException("failed to load --zDistanceJson " + this.zDistanceJson, ioe);
            }
        }
        LOG.info("initDefaultValues: normalizedDistances={}", this.normalizedDistances);
    }

    public int getMaxZDistance() {
        return normalizedDistances.stream()
                .map(DistanceListForRegion::getMaxDistance)
                .max(Integer::compareTo)
                .orElse(0);
    }

    /**
     * @return true if the specified tile pair should be included in processing
     *         given this set of zDistance specifications.
     */
    public boolean includePair(final TileBounds pBounds,
                               final TileBounds qBounds) {
        // Using intValue for zDistance because 23.0, 23.1, ..., 23.8 should all map to 23.
        // The "hack" use of double values for z was never intended to have precision.
        // Almost all stacks will have .0 z values while a few legacy TEM stacks will have .1 or .2 z values.
        final int zDistance = Math.abs(pBounds.getZ().intValue() - qBounds.getZ().intValue());
        return normalizedDistances.stream().anyMatch(s -> s.includePair(pBounds, qBounds, zDistance));
    }

    @Override
    public String toString() {
        return String.valueOf(normalizedDistances);
    }

    /**
     * List of integral distances for a region (or all regions if bounds == null).
     */
    public static class DistanceListForRegion implements Serializable {
        private List<Integer> distanceList;
        private final Bounds bounds;

        @SuppressWarnings("unused")
        // needed for JSON deserialization
        private DistanceListForRegion() {
            this(null, null);
        }

        /**
         * Construct a distance that applies to all tiles.
         */
        public DistanceListForRegion(final List<Integer> distanceList) {
            this(distanceList, null);
        }

        /**
         * Construct a distance that applies only to tiles that are completely within the specified bounds.
         */
        public DistanceListForRegion(final List<Integer> valueList,
                                     final Bounds bounds)
                throws IllegalArgumentException {

            normalizeDistanceList(valueList);
            this.bounds = bounds;
        }

        public List<Integer> getDistanceList() {
            return distanceList;
        }

        /**
         * Sets this object's distanceList to a copy of the specified list that is normalized.
         * Normalization adds inferred values, makes the list distinct, and sorts the list.
         * The method has been extracted from the constructor so that it can be called after
         * deserializing JSON representations.
         */
        public void normalizeDistanceList(final List<Integer> valueList)
                throws IllegalArgumentException {

            final List<Integer> normalizedList = new ArrayList<>();
            normalizedList.add(0);

            if (valueList != null) {
                if (valueList.stream().anyMatch(z -> z < 0)) {
                    throw new IllegalArgumentException("zDistance must not contain negative values");
                } else {
                    normalizedList.addAll(valueList);
                }
            }

            this.distanceList = normalizedList.stream().distinct().sorted().collect(Collectors.toList());
        }

        public int getMaxDistance() {
            // list is always sorted and has at least one element (0), so just return last element
            return distanceList.get(distanceList.size() - 1);
        }

        /**
         * @return true if the specified tile pair has an integral zDistance within this list of distances.
         *         If this list has a defined region, then both tiles must also be completely within
         *         the region's bounds for true to be returned.
         */
        public boolean includePair(final TileBounds pBounds,
                                   final TileBounds qBounds,
                                   final int zDistance) {
            final boolean pairInRegion = (this.bounds == null) ||
                                         (this.bounds.containsInt(pBounds) && this.bounds.containsInt(qBounds));
            return this.distanceList.contains(zDistance) && pairInRegion;
        }

        @Override
        public String toString() {
            return "{\"distanceList\":" + distanceList + ", \"bounds\":" + bounds + '}';
        }

        public static List<DistanceListForRegion> fromJsonArray(final Reader json)
                throws IllegalArgumentException {
            try {
                return Arrays.asList(JsonUtils.MAPPER.readValue(json, DistanceListForRegion[].class));
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ZDistanceParameters.class);
}
