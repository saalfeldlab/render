package org.janelia.acquire.client;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;

/**
 * Data returned by the Image Catcher next-unsolved-tile API.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class AcquisitionTile {

    public enum ResultType {
        NO_TILE_READY, TILE_FOUND, SERVED_ALL_ACQ, SERVED_ALL_SECTION, NO_TILE_READY_IN_SECTION
    }

    private final String acqid;
    private final String section;
    private final TileSpec tilespec;
    private final ResultType resultType;

    private AcquisitionTile() {
        this(null, null, null, null);
    }

    public AcquisitionTile(final String acqid,
                           final ResultType resultType,
                           final String section,
                           final TileSpec tileSpec) {
        this.acqid = acqid;
        this.resultType = resultType;
        this.section = section;
        this.tilespec = tileSpec;
    }

    public TileSpec getTileSpec() {
        return tilespec;
    }

    public String getTileSpecId() {
        String tileSpecId = null;
        if (tilespec != null) {
            tileSpecId = tilespec.getTileId();
        }
        return tileSpecId;
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

    private static final JsonUtils.Helper<AcquisitionTile> JSON_HELPER =
            new JsonUtils.Helper<>(AcquisitionTile.class);

}
