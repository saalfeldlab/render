package org.janelia.acquire.client.model;

import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * List of acquisition tile ids with a common state.
 *
 * @author Eric Trautman
 */
public class AcquisitionTileIdList {

    private final AcquisitionTileState state;
    private final List<String> tileSpecIds;

    @SuppressWarnings("unused")
    private AcquisitionTileIdList() {
        this(null, null);
    }

    public AcquisitionTileIdList(final AcquisitionTileState state,
                                 final List<String> tileSpecIds) {
        this.state = state;
        this.tileSpecIds = tileSpecIds;
    }

    public void addTileSpecId(final String tileSpecId) {
        tileSpecIds.add(tileSpecId);
    }

    public int size() {
        int size = 0;
        if (tileSpecIds != null) {
            size = tileSpecIds.size();
        }
        return size;
    }

    @Override
    public String toString() {
        return "{ \"state\": \"" + state + "\", \"tileSpecIds\": [ \"" + size() + " values ...\" ] }";
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<AcquisitionTileIdList> JSON_HELPER =
            new JsonUtils.Helper<>(AcquisitionTileIdList.class);

}
