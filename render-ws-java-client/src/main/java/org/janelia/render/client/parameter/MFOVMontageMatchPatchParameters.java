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
import org.janelia.alignment.multisem.MultiSemUtilities;

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
            description = "Multi-field-of-view identifier <magcNumber>_m<mfovNumber> (e.g. 0399_m0013).  " +
                          "Omit to process all MFOVs in stack.")
    public String multiFieldOfViewId;

    @Parameter(
            names = "--sameLayerDerivedMatchWeight",
            description = "Weight (e.g. 0.15) for matches derived from same z layer.  " +
                          "Omit to skip same layer derivation.")
    public Double sameLayerDerivedMatchWeight;

    @Parameter(
            names = "--crossLayerDerivedMatchWeight",
            description = "Weight (e.g. 0.1) for matches derived from other z layers.  " +
                          "Omit to skip cross layer derivation.")
    public Double crossLayerDerivedMatchWeight;

    @Parameter(
            names = "--secondPassDerivedMatchWeight",
            description = "Weight for all matches derived after first pass same and cross layer derivation.  " +
                          "Omit to skip second pass derivation.  " +
                          "Only valid for spark jobs (ignored for java client jobs).")
    public Double secondPassDerivedMatchWeight;

    @Parameter(
            names = "--startPositionMatchWeight",
            description = "Weight (e.g. 0.000001) for matches derived from tile start position.  " +
                          "Omit to skip start position derivation.")
    public Double startPositionMatchWeight;

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
    public String pMagcMfovSfovPrefix;

    @Parameter(
            names = "--qTileId",
            description = "Only derive matches for positions associated with this q tile (overrides --mfov parameter)"
    )
    public String qTileId;
    public String qMagcMfovSfovPrefix;

    @Parameter(
            names = "--matchStorageFile",
            description = "File to store matches (omit if matches should be stored through web service)"
    )
    public String matchStorageFile;

    @Parameter(
            names = "--numberOfMFOVsPerBatch",
            description = "Number of MFOVs to process in each batch")
    public int numberOfMFOVsPerBatch = 1;

    @Parameter(
            names = "--trimMfovsWithNoConnectedTiles",
            description = "If specified, create a 'trim' copy of the source stack that excludes any unconnected " +
                          "tiles that exist in a z-layer MFOV in which all of its tiles are completely unconnected. " +
                          "Note that this option is currently only supported for Spark jobs.",
            arity = 0)
    public boolean trimMfovsWithNoConnectedTiles = false;

    @Parameter(
            names = "--patchAllUnconnectedPairsWithStageCoordinates",
            description = "If specified, patch all remaining unconnected pairs with positions based upon SFOV stage locations.  " +
                          "Omit sameLayerDerivedMatchWeight, crossLayerDerivedMatchWeight, and secondPassDerivedMatchWeight " +
                          "parameters if you want to patch everything with these stage locations.",
            arity = 0)
    public boolean patchAllUnconnectedPairsWithStageCoordinates = false;

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
        clonedParameters.secondPassDerivedMatchWeight = this.secondPassDerivedMatchWeight;
        clonedParameters.startPositionMatchWeight = this.startPositionMatchWeight;
        clonedParameters.xyNeighborFactor = this.xyNeighborFactor;
        clonedParameters.pTileId = this.pTileId;
        clonedParameters.qTileId = this.qTileId;
        clonedParameters.matchStorageFile = this.matchStorageFile;
        clonedParameters.trimMfovsWithNoConnectedTiles = this.trimMfovsWithNoConnectedTiles;
        clonedParameters.patchAllUnconnectedPairsWithStageCoordinates = this.patchAllUnconnectedPairsWithStageCoordinates;
        clonedParameters.validateAndSetupDerivedValues(); // make sure values derived from multiFieldOfViewId are rebuilt
        return clonedParameters;
    }

    public void setWeightsForSecondPass() {
        this.sameLayerDerivedMatchWeight = this.secondPassDerivedMatchWeight;
        this.crossLayerDerivedMatchWeight = this.secondPassDerivedMatchWeight;
    }

    public String getMultiFieldOfViewId() {
        String mFOVId = multiFieldOfViewId;
        if (mFOVId == null) {
            if (pTileId != null) {
                mFOVId = MultiSemUtilities.getMagcMfovForTileId(pTileId);
            } else if (qTileId != null) {
                mFOVId = MultiSemUtilities.getMagcMfovForTileId(qTileId);
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
     * @return the specified stackMFOVWithZValuesList bundled into groups of numberOfMFOVsPerBatch.
     */
    public List<List<StackMFOVWithZValues>> bundleMFOVs(final List<StackMFOVWithZValues> stackMFOVWithZValuesList) {
        final List<List<StackMFOVWithZValues>> bundles = new ArrayList<>();
        List<StackMFOVWithZValues> currentBundle = new ArrayList<>();
        for (final StackMFOVWithZValues stackMFOVWithZValues : stackMFOVWithZValuesList) {
            currentBundle.add(stackMFOVWithZValues);
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

    public String getTrimStackName(final String sourceStackName) {
        return trimMfovsWithNoConnectedTiles ? sourceStackName + "_trim" : null;
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
            multiFieldOfViewId = MultiSemUtilities.getMagcMfovForTileId(pTileId);
            pMagcMfovSfovPrefix = MultiSemUtilities.getMagcMfovSfovForTileId(pTileId);
            if (qTileId != null) {
                if (! multiFieldOfViewId.equals(MultiSemUtilities.getMagcMfovForTileId(qTileId))) {
                    throw new IllegalArgumentException("pTileId and qTileId reference different MFOVs");
                }
                qMagcMfovSfovPrefix = MultiSemUtilities.getMagcMfovSfovForTileId(qTileId);
            } else {
                qMagcMfovSfovPrefix = multiFieldOfViewId;
            }
        } else if (qTileId != null) {
            multiFieldOfViewId = MultiSemUtilities.getMagcMfovForTileId(qTileId);
            qMagcMfovSfovPrefix = MultiSemUtilities.getMagcMfovSfovForTileId(qTileId);
            pMagcMfovSfovPrefix = multiFieldOfViewId;
        } else if ((multiFieldOfViewId == null) || (multiFieldOfViewId.length() != 10)) {
            throw new IllegalArgumentException("--mfov should be a 10 character value (e.g. 0399_m0013)");
        } else {
            pMagcMfovSfovPrefix = multiFieldOfViewId;
            qMagcMfovSfovPrefix = multiFieldOfViewId;
        }

        if (matchStorageFile != null) {
            MultiSemUtilities.validateMatchStorageLocation(matchStorageFile);
        }
    }

    private static final JsonUtils.Helper<MFOVMontageMatchPatchParameters> JSON_HELPER =
            new JsonUtils.Helper<>(MFOVMontageMatchPatchParameters.class);
}
