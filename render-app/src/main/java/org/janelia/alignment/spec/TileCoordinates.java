package org.janelia.alignment.spec;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import mpicbg.models.NoninvertibleModelException;

import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinate data associated with a tile.
 *
 * @author Eric Trautman
 */
public class TileCoordinates implements Serializable {

    private String tileId;
    private Boolean visible;
    private final double[] local;
    private final double[] world;
    private String error;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private TileCoordinates() {
        this.tileId = null;
        this.visible = null;
        this.local = null;
        this.world = null;
        this.error = null;
    }

    public TileCoordinates(final String tileId,
                           final double[] local,
                           final double[] world) {
        this.tileId = tileId;
        this.visible = null;
        this.local = local;
        this.world = world;
        this.error = null;
    }

    public String getTileId() {
        return tileId;
    }

    public void setTileId(final String tileId) {
        this.tileId = tileId;
    }

    public boolean isVisible() {
        return ((visible != null) && visible);
    }

    public void setVisible(final Boolean visible) {
        this.visible = visible;
    }

    public double[] getLocal() {
        return local;
    }

    public double[] getWorld() {
        return world;
    }

    public boolean hasError() {
        return (error != null);
    }

    public String getError() {
        return error;
    }

    public void setError(final String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public static TileCoordinates buildLocalInstance(final String tileId,
                                                     final double[] local) {
        return new TileCoordinates(tileId, local, null);
    }

    public static TileCoordinates buildWorldInstance(final String tileId,
                                                     final double[] world) {
        return new TileCoordinates(tileId, null, world);
    }

    /**
     * @param  tileSpecList  list of tiles that contain the specified point
     *                       (order of list is assumed to be the same order used for rendering).
     *
     * @param  x             x coordinate.
     * @param  y             y coordinate.
     *
     * @return a local {@link TileCoordinates} instance with the inverse of the specified world point.
     *
     * @throws IllegalStateException
     *   if the specified point cannot be inverted for any of the specified tiles.
     */
    public static List<TileCoordinates> getLocalCoordinates(
            final List<TileSpec> tileSpecList,
            final double x,
            final double y)
            throws IllegalStateException {


        final List<TileCoordinates> tileCoordinatesList = new ArrayList<>();
        List<String> nonInvertibleTileIds = null;
        double[] local;
        TileCoordinates tileCoordinates;
        for (final TileSpec tileSpec : tileSpecList) {
            try {
                local = tileSpec.getLocalCoordinates(x, y, tileSpec.getMeshCellSize());
                tileCoordinates = buildLocalInstance(tileSpec.getTileId(), local);
                tileCoordinatesList.add(tileCoordinates);
            } catch (final NoninvertibleModelException e) {
                if (nonInvertibleTileIds == null) {
                    nonInvertibleTileIds = new ArrayList<>();
                }
                nonInvertibleTileIds.add(tileSpec.getTileId());
            }
        }

        final int numberOfInvertibleCoordinates = tileCoordinatesList.size();
        if (numberOfInvertibleCoordinates == 0) {
            throw new IllegalStateException("world coordinate (" + x + ", " + y + ") found in tile id(s) " +
                                            nonInvertibleTileIds + " cannot be inverted");
        } else {
            // Tiles are rendered in same order as specified tileSpecList.
            // Consequently for overlapping regions, the last tile will be the visible one
            // since it is rendered after or "on top of" the previous tile(s).
            final TileCoordinates lastTileCoordinates = tileCoordinatesList.get(numberOfInvertibleCoordinates - 1);
            lastTileCoordinates.setVisible(true);
        }

        if (nonInvertibleTileIds != null) {
            LOG.info("getLocalCoordinates: skipped inverse transform of ({}, {}) for non-invertible tile id(s) {}, used tile id {} instead",
                     x, y, nonInvertibleTileIds, tileCoordinatesList.get(0).getTileId());
        }

        return tileCoordinatesList;
    }

    public static TileCoordinates getWorldCoordinates(final TileSpec tileSpec,
                                                      final double x,
                                                      final double y) {
        final double[] world = tileSpec.getWorldCoordinates(x, y);
        return buildWorldInstance(tileSpec.getTileId(), world);
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static TileCoordinates fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static List<TileCoordinates> fromJsonArray(final Reader json) {
        // TODO: verify using Arrays.asList optimization is actually faster
        // return JSON_HELPER.fromJsonArray(json);
        try {
            return Arrays.asList(JsonUtils.MAPPER.readValue(json, TileCoordinates[].class));
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<List<TileCoordinates>> fromJsonArrayOfArrays(final String json) {
        // TODO: see if this can be done with faster arrays instead of TypeReference
        try {
            return JsonUtils.MAPPER.readValue(json, LIST_OF_LISTS_TYPE);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<List<TileCoordinates>> fromJsonArrayOfArrays(final Reader json) {
        // TODO: see if this can be done with faster arrays instead of TypeReference
        try {
            return JsonUtils.MAPPER.readValue(json, LIST_OF_LISTS_TYPE);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileCoordinates.class);

    private static final JsonUtils.Helper<TileCoordinates> JSON_HELPER =
            new JsonUtils.Helper<>(TileCoordinates.class);

    private static final TypeReference<List<List<TileCoordinates>>> LIST_OF_LISTS_TYPE =
            new TypeReference<List<List<TileCoordinates>>>(){};
}
