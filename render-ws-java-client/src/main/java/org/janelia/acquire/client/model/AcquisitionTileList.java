package org.janelia.acquire.client.model;

import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Image Catcher wrapper for list of acquisition tile data returned by the next-unsolved-tile API.
 *
 * @author Eric Trautman
 */
public class AcquisitionTileList {

    public enum ResultType {
        NO_TILE_READY, TILE_FOUND, SERVED_ALL_ACQ, SERVED_ALL_SECTION, NO_TILE_READY_IN_SECTION
    }

    private ResultType resultType;
    private List<AcquisitionTile> results;

    @SuppressWarnings("unused")
    public AcquisitionTileList() {
    }

    public AcquisitionTileList(final ResultType resultType,
                               final List<AcquisitionTile> results) {
        this.resultType = resultType;
        this.results = results;
    }

    public List<AcquisitionTile> getResults() {
        return results;
    }

    public ResultType getResultType() {
        return resultType;
    }

    @Override
    public String toString() {
        return toJson();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<AcquisitionTileList> JSON_HELPER =
            new JsonUtils.Helper<>(AcquisitionTileList.class);

}
