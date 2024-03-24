package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.multisem.StackMFOVWithZValues;
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
            description = "Multi-field-of-view identifier <slab number>_<mfov number> (e.g. 001_000006).  " +
                          "Omit to process all MFOVs in stack.")
    public String multiFieldOfViewId;

    @Parameter(
            names = "--sameLayerDerivedMatchWeight",
            description = "Weight for matches derived from same z layer (e.g. 0.15).  " +
                          "Omit to skip same layer derivation.")
    public Double sameLayerDerivedMatchWeight;

    @Parameter(
            names = "--crossLayerDerivedMatchWeight",
            description = "Weight for matches derived from other z layers (e.g. 0.1)",
            required = true)
    public Double crossLayerDerivedMatchWeight;

    @Parameter(
            names = "--secondPassDerivedMatchWeight",
            description = "Weight for all matches derived after first pass same and cross layer derivation.  " +
                          "Omit to skip second pass derivation.  " +
                          "Only valid for spark jobs (ignored for java client jobs).")
    public Double secondPassDerivedMatchWeight;

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

    @Parameter(
            names = "--numberOfMFOVsPerBatch",
            description = "Number of MFOVs to process in each batch")
    public int numberOfMFOVsPerBatch = 1;

    public MFOVMontageMatchPatchParameters() {
    }

    /**
     * @return clone of these parameters with the specified multiFieldOfViewId.
     *         Clone has derived values populated and validated.
     *
     * @throws IllegalArgumentException
     *   if the specified multiFieldOfViewId is inconsistent with other parameters (e.g. tileIds).
     */
    public MFOVMontageMatchPatchParameters withMultiFieldOfViewId(final String multiFieldOfViewIdForClone)
            throws IllegalArgumentException {
        final MFOVMontageMatchPatchParameters clonedParameters = new MFOVMontageMatchPatchParameters();
        clonedParameters.matchStorageCollection = this.matchStorageCollection;
        clonedParameters.multiFieldOfViewId = multiFieldOfViewIdForClone;
        clonedParameters.sameLayerDerivedMatchWeight = this.sameLayerDerivedMatchWeight;
        clonedParameters.crossLayerDerivedMatchWeight = this.crossLayerDerivedMatchWeight;
        clonedParameters.xyNeighborFactor = this.xyNeighborFactor;
        clonedParameters.pTileId = this.pTileId;
        clonedParameters.qTileId = this.qTileId;
        clonedParameters.matchStorageFile = this.matchStorageFile;
        clonedParameters.validateAndSetupDerivedValues(); // make sure values derived from multiFieldOfViewId are rebuilt
        return clonedParameters;
    }

    public void setWeightsForSecondPass() {
        this.sameLayerDerivedMatchWeight = this.secondPassDerivedMatchWeight;
        this.crossLayerDerivedMatchWeight = this.secondPassDerivedMatchWeight;
    }

    // 001_000006_019_20220407_115555.1247.0 => 001_000006_019
    public String getTileIdPrefixForRun(final String tileId) throws IllegalArgumentException {
        if (tileId.length() < 14) {
            throw new IllegalArgumentException("MFOV position cannot be derived from tileId " + tileId);
        }
        return tileId.substring(0, 14);
    }

    public String getMultiFieldOfViewId() {
        String mFOVId = multiFieldOfViewId;
        if (mFOVId == null) {
            if (pTileId != null) {
                mFOVId = Utilities.getMFOVForTileId(pTileId);
            } else if (qTileId != null) {
                mFOVId = Utilities.getMFOVForTileId(qTileId);
            }
        }
        return mFOVId;
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

    /**
     * @return the specified stackMFOVWithZValues list bundled into groups of numberOfMFOVsPerBatch.
     */
    public List<List<StackMFOVWithZValues>> bundleMFOVs(final List<StackMFOVWithZValues> stackMFOVWithZValues) {
        final List<List<StackMFOVWithZValues>> bundles = new ArrayList<>();
        List<StackMFOVWithZValues> currentBundle = new ArrayList<>();
        for (final StackMFOVWithZValues stackMFOVWithZValue : stackMFOVWithZValues) {
            currentBundle.add(stackMFOVWithZValue);
            if (currentBundle.size() == numberOfMFOVsPerBatch) {
                bundles.add(currentBundle);
                currentBundle = new ArrayList<>();
            }
        }
        if (! currentBundle.isEmpty()) {
            bundles.add(currentBundle);
        }
        return bundles;
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

    private void validateAndSetupDerivedValues()
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

    private static final JsonUtils.Helper<MFOVMontageMatchPatchParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MFOVMontageMatchPatchParameters.class);
}
