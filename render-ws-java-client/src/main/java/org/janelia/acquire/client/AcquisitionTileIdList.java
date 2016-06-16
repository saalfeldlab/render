package org.janelia.acquire.client;

import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * List of acquisition tile ids with a common state.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class AcquisitionTileIdList {

    private final AcquisitionTileState state;
    private final List<String> tileSpecIds;

    private AcquisitionTileIdList() {
        this(null, null);
    }

    public AcquisitionTileIdList(final AcquisitionTileState state,
                                 final List<String> tileSpecIds) {
        this.state = state;
        this.tileSpecIds = tileSpecIds;
    }

    public int size() {
        return tileSpecIds.size();
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<AcquisitionTileIdList> JSON_HELPER =
            new JsonUtils.Helper<>(AcquisitionTileIdList.class);

}
