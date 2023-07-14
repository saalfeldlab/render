package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.multisem.Utilities;

/**
 * Parameters for MFOV montage match patching.
 *
 * @author Eric Trautman
 */
public class MFOVMontageMatchPatchParameters
        implements Serializable {

    @Parameter(
            names = "--matchStorageCollection",
            description = "Collection for storage of derived matches (omit to store to source collection)")
    public String matchStorageCollection;

    @Parameter(
            names = "--mfov",
            description = "Multi-field-of-view identifier <slab number>_<mfov number> (e.g. 001_000006)")
    public String multiFieldOfViewId;

    @Parameter(
            names = "--storedMatchWeight",
            description = "Weight for stored matches (e.g. 0.0001)",
            required = true)
    public Double storedMatchWeight;

    @Parameter(
            names = "--xyNeighborFactor",
            description = "Multiply this by max(width, height) of each tile to determine radius for locating neighbor tiles",
            required = true
    )
    public Double xyNeighborFactor;

    @Parameter(
            names = "--pTileId",
            description = "Only derive matches for positions associated with this p tile (overrides --mfov parameter)"
    )
    public String pTileId;
    public String pTileIdPrefixForRun;

    @Parameter(
            names = "--qTileId",
            description = "Only derive matches for positions associated with this q tile (overrides --mfov parameter)"
    )
    public String qTileId;
    public String qTileIdPrefixForRun;

    @Parameter(
            names = "--matchStorageFile",
            description = "File to store matches (omit if matches should be stored through web service)"
    )
    public String matchStorageFile;

    public MFOVMontageMatchPatchParameters() {
    }

    // 001_000006_019_20220407_115555.1247.0 => 001_000006_019
    public String getTileIdPrefixForRun(final String tileId) throws IllegalArgumentException {
        if (tileId.length() < 14) {
            throw new IllegalArgumentException("MFOV position cannot be derived from tileId " + tileId);
        }
        return tileId.substring(0, 14);
    }

    public void validateAndSetupDerivedValues()
            throws IllegalArgumentException {

        if (pTileId != null) {
            multiFieldOfViewId = Utilities.getMFOVForTileId(pTileId);
            pTileIdPrefixForRun = getTileIdPrefixForRun(pTileId);
            if (qTileId != null) {
                if (! multiFieldOfViewId.equals(Utilities.getMFOVForTileId(qTileId))) {
                    throw new IllegalArgumentException("pTileId and qTileId reference different MFOVs");
                }
                qTileIdPrefixForRun = getTileIdPrefixForRun(qTileId);
            } else {
                qTileIdPrefixForRun = multiFieldOfViewId;
            }
        } else if (qTileId != null) {
            multiFieldOfViewId = Utilities.getMFOVForTileId(qTileId);
            qTileIdPrefixForRun = getTileIdPrefixForRun(qTileId);
            pTileIdPrefixForRun = multiFieldOfViewId;
        } else if ((multiFieldOfViewId == null) || (multiFieldOfViewId.length() != 10)) {
            throw new IllegalArgumentException("--mfov should be a 10 character value (e.g. 001_000006)");
        } else {
            pTileIdPrefixForRun = multiFieldOfViewId;
            qTileIdPrefixForRun = multiFieldOfViewId;
        }

        if (matchStorageFile != null) {
            Utilities.validateMatchStorageLocation(matchStorageFile);
        }
    }

    public void setMultiFieldOfViewId(final String multiFieldOfViewId) {
        this.multiFieldOfViewId = multiFieldOfViewId;
        this.validateAndSetupDerivedValues(); // make sure values derived from multiFieldOfViewId are rebuilt
    }

    public String getMatchStorageCollectionName(final MatchCollectionId sourceCollectionId) {
        return matchStorageCollection == null ? sourceCollectionId.getName() : matchStorageCollection;
    }

    /*
     * @return JSON representation of these parameters.
     */
    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static MFOVMontageMatchPatchParameters fromJson(final Reader json) {
        return JSON_HELPER.fromJson(json);
    }

    public static MFOVMontageMatchPatchParameters fromJsonFile(final String dataFile)
            throws IOException {
        final MFOVMontageMatchPatchParameters parameters;
        final Path path = FileSystems.getDefault().getPath(dataFile).toAbsolutePath();
        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            parameters = fromJson(reader);
        }
        return parameters;
    }

    private static final JsonUtils.Helper<MFOVMontageMatchPatchParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MFOVMontageMatchPatchParameters.class);
}
