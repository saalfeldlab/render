package org.janelia.acquire.client.model;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;

/**
 * Data returned by the Image Catcher next-unsolved-tile API.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class AcquisitionTile {

    private final String acqid;
    private final String section;
    private final TileSpec tilespec;

    private AcquisitionTile() {
        this(null, null, null);
    }

    public AcquisitionTile(final String acqid,
                           final String section,
                           final TileSpec tileSpec) {
        this.acqid = acqid;
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
