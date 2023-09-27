package org.janelia.alignment.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.models.Affine2D;
import mpicbg.models.CoordinateTransform;
import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.ApiModelProperty;

import static org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod.*;

/**
 * A collection of tile specifications that also includes all referenced transform specifications,
 * allowing the tile specifications to be fully resolved.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("JavadocBlankLines")
public class ResolvedTileSpecCollection implements Serializable {

    public enum TransformApplicationMethod {
        /** Indicates that the specified transform should be appended to the end of each tile's transform list. */
        APPEND,

        /** Indicates that the specified transform should be inserted as the first transform for each tile. */
        INSERT_AS_FIRST,

        /** Indicates that the specified transform should be inserted before each tile's last transform. */
        INSERT_BEFORE_LAST,

        /** Indicates that the specified transform should replace each tile's last transform. */
        REPLACE_LAST,

        /**
         * Indicates that the specified transform should be pre-concatenated to each tile's last transform
         * (only valid if transforms are affine).
         */
        PRE_CONCATENATE_LAST
    }

    private final Map<String, TransformSpec> transformIdToSpecMap;
    private final Map<String, TileSpec> tileIdToSpecMap;

    private transient TileSpecValidator tileSpecValidator;

    // no-arg constructor needed for JSON deserialization
    public ResolvedTileSpecCollection() {
        this(new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Creates a collection containing the provided transform and tile specs.
     *
     * @param  transformSpecs  shared (referenced) transform specifications.
     * @param  tileSpecs       tile specifications.
     *
     * @throws IllegalArgumentException
     *   if any of the tile specifications reference an unknown transform specification.
     */
    public ResolvedTileSpecCollection(final Collection<TransformSpec> transformSpecs,
                                      final Collection<TileSpec> tileSpecs)
            throws IllegalArgumentException {

        this.transformIdToSpecMap = new HashMap<>(transformSpecs.size() * 2);
        this.tileIdToSpecMap = new HashMap<>(tileSpecs.size() * 2);
        this.tileSpecValidator = null;

        transformSpecs.forEach(this::addTransformSpecToCollection);
        tileSpecs.forEach(this::addTileSpecToCollection);
    }

    /**
     * Merges transforms and tiles from the specified collection into this one.
     */
    public void merge(final ResolvedTileSpecCollection anotherCollection) {
        for (final TransformSpec transformSpec : anotherCollection.getTransformSpecs()) {
            this.addTransformSpecToCollection(transformSpec);
        }
        for (final TileSpec tileSpec : anotherCollection.getTileSpecs()) {
            this.addTileSpecToCollection(tileSpec);
        }
        if (this.tileSpecValidator == null) {
            this.tileSpecValidator = anotherCollection.tileSpecValidator;
        }
    }

    /**
     * Sets the tile spec validator for this collection (a null value disables validation).
     * Any tile spec identified as invalid by this validator will be removed / filtered from the collection.
     *
     * @param  tileSpecValidator  validator to use for all tile specs.
     */
    public void setTileSpecValidator(final TileSpecValidator tileSpecValidator) {
        this.tileSpecValidator = tileSpecValidator;
    }

    /**
     * @return true if the a tile spec with the specified id exits in this collection; otherwise false.
     */
    public boolean hasTileSpec(final String tileId) {
        return tileIdToSpecMap.containsKey(tileId);
    }

    /**
     * @param  tileId  identifier for the desired tile.
     *
     * @return the tile specification with the specified id (or null if it does not exist).
     */
    public TileSpec getTileSpec(final String tileId) {
        return tileIdToSpecMap.get(tileId);
    }

    /**
     * @return the set of resolved tile specifications in this collection.
     *
     * @throws IllegalArgumentException
     *   if any of the tile specifications reference an unknown transform specification.
     */
    @JsonIgnore
    public Collection<TileSpec> getTileSpecs()
            throws IllegalArgumentException {
        resolveTileSpecs(); // this needs to be done here for collections deserialized from JSON
        return tileIdToSpecMap.values();
    }

    public Map<String, TileSpec> getTileIdToSpecMap() {
        return tileIdToSpecMap;
    }

    public Map<String, TransformSpec> getTransformIdToSpecMap() {
        return transformIdToSpecMap;
    }

    /**
     * @return set of tile ids in this collection.
     */
    @JsonIgnore
    public Set<String> getTileIds() {
        return tileIdToSpecMap.keySet();
    }

    /**
     * Adds a tile specification to this collection and verifies that
     * any referenced transforms already exist in this collection.
     *
     * @param  tileSpec  spec to add.
     *
     * @throws IllegalArgumentException
     *   if the tile specification references an unknown transform specification.
     */
    public void addTileSpecToCollection(final TileSpec tileSpec)
            throws IllegalArgumentException {
        resolveTileSpec(tileSpec);
        tileIdToSpecMap.put(tileSpec.getTileId(), tileSpec);
    }

    /**
     * @return the set of shared transform specifications in this collection.
     */
    @JsonIgnore
    public Collection<TransformSpec> getTransformSpecs() {
        return transformIdToSpecMap.values();
    }

    /**
     * Adds a transform specification to this collection's set of shared transformations.
     *
     * @param  transformSpec  spec to add.
     */
    public void addTransformSpecToCollection(final TransformSpec transformSpec) {
        transformIdToSpecMap.put(transformSpec.getId(), transformSpec);
    }

    /**
     * Adds a transform specification to the specified tile.
     *
     * The tile's bounding box is recalculated after the new transform is applied.
     *
     * If this collection has a tile spec validator that determines the spec is invalid
     * (after applying the transform), the spec will be removed from the collection.
     *
     * @param  tileId             identifies the tile to which the transform should be added.
     *
     * @param  transformSpec      the transform to add.
     *
     * @param  applicationMethod  method used to apply (add) the transform.
     *
     * @throws IllegalArgumentException
     *   if the specified tile cannot be found or the specified transform cannot be fully resolved.
     */
    public void addTransformSpecToTile(final String tileId,
                                       final TransformSpec transformSpec,
                                       final TransformApplicationMethod applicationMethod) throws IllegalArgumentException {

        final TileSpec tileSpec = tileIdToSpecMap.get(tileId);

        if (tileSpec == null) {
            throw new IllegalArgumentException("tile spec with id '" + tileId + "' not found");
        }

        if (! transformSpec.isFullyResolved()) {
            transformSpec.resolveReferences(transformIdToSpecMap);
            if (! transformSpec.isFullyResolved()) {
                throw new IllegalArgumentException("transform spec references the following unresolved transform ids " +
                                                   transformSpec.getUnresolvedIds());
            }
        }

        final List<TransformSpec> newTransformSpecList = new ArrayList<>();
        switch (applicationMethod) {
            case APPEND:
                newTransformSpecList.add(transformSpec);
                break;

            case INSERT_AS_FIRST:
                newTransformSpecList.add(transformSpec);
                final ListTransformSpec existingTransformSpecs = tileSpec.getTransforms();
                for (int i = 0; i < existingTransformSpecs.size(); i++) {
                    newTransformSpecList.add(existingTransformSpecs.getSpec(i));
                }
                tileSpec.setTransforms(new ListTransformSpec()); // remove existing transform list
                break;

            case INSERT_BEFORE_LAST:
                final TransformSpec lastTransform = tileSpec.getLastTransform();
                newTransformSpecList.add(transformSpec);
                if (lastTransform != null) {
                    newTransformSpecList.add(lastTransform);
                    tileSpec.removeLastTransformSpec();
                }
                break;

            case REPLACE_LAST:
                newTransformSpecList.add(transformSpec);
                tileSpec.removeLastTransformSpec();
                break;

            case PRE_CONCATENATE_LAST:
                final AffineModel2D lastAffine = getAffineModelForSpec("last",
                                                                       tileSpec.getLastTransform());
                final AffineModel2D preConcatenatedAffine = getAffineModelForSpec("pre-concatenated",
                                                                                  transformSpec);
                lastAffine.preConcatenate(preConcatenatedAffine);
                newTransformSpecList.add(new LeafTransformSpec(AffineModel2D.class.getName(),
                                                               lastAffine.toDataString()));

                tileSpec.removeLastTransformSpec();
                break;
        }

        tileSpec.addTransformSpecs(newTransformSpecList);

        // addition of new transform spec obsolesces the previously resolved coordinate transform instance,
        // so we need to re-resolve the tile before re-deriving the bounding box
        resolveTileSpec(tileSpec);

        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
    }

    public void recalculateBoundingBoxes() {
        for (final TileSpec tileSpec : tileIdToSpecMap.values()) {
            // re-resolve each tile to pick up any changes
            resolveTileSpec(tileSpec);
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
        }
    }

    /**
     * Adds a reference to the specified transform to all tiles in this collection.
     *
     * Each tile's bounding box is recalculated after the new transform is applied
     * (so this can potentially be a long running operation).
     *
     * If this collection has a tile spec validator that determines one or more tile specs are invalid
     * (after applying the transform), those tile specs will be removed from the collection.
     *
     * @param  transformId    identifies the transform to be applied to all tiles.
     *
     * @param  applicationMethod  method used to apply (add) the transform.
     *
     * @throws IllegalArgumentException
     *   if the specified transform cannot be found.
     */
    public void addReferenceTransformToAllTiles(final String transformId,
                                                final TransformApplicationMethod applicationMethod)
            throws IllegalArgumentException {
        addReferenceTransformToTilesWithIds(transformId, tileIdToSpecMap.keySet(), applicationMethod);
    }

    /**
     * Adds a reference to the specified transform to all tiles with ids in the specified set.
     *
     * Each tile's bounding box is recalculated after the new transform is applied
     * (so this can potentially be a long running operation).
     *
     * If this collection has a tile spec validator that determines one or more tile specs are invalid
     * (after applying the transform), those tile specs will be removed from the collection.
     *
     * @param  transformId    identifies the transform to be applied.
     *
     * @param  tileIds        identifies tiles to which the transform should be applied.
     *
     * @param  applicationMethod  method used to apply (add) the transform.
     *
     * @throws IllegalArgumentException
     *   if the specified transform cannot be found.
     */
    public void addReferenceTransformToTilesWithIds(final String transformId,
                                                    final Set<String> tileIds,
                                                    final TransformApplicationMethod applicationMethod)
            throws IllegalArgumentException {

        final TransformSpec transformSpec = transformIdToSpecMap.get(transformId);
        if (transformSpec == null) {
            throw new IllegalArgumentException("transform " + transformId + " cannot be found");
        }

        final TransformSpec referenceTransformSpec = new ReferenceTransformSpec(transformId);

        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        for (final String tileId : tileIds) {
            addTransformSpecToTile(tileId, referenceTransformSpec, applicationMethod);
            tileSpecCount++;
            if (timer.hasIntervalPassed()) {
                LOG.info("addReferenceTransformToTilesWithIds: added transform to {} out of {} tiles",
                         tileSpecCount, tileIds.size());
            }
        }

        LOG.info("addReferenceTransformToTilesWithIds: added transform to {} tiles, elapsedSeconds={}",
                 tileSpecCount, timer.getElapsedSeconds());
    }

    /**
     * Concatenates the specified transform to the last transform of each tile in this collection.
     *
     * Each tile's bounding box is recalculated after the new transform is applied
     * (so this can potentially be a long running operation).
     *
     * If this collection has a tile spec validator that determines one or more tile specs are invalid
     * (after applying the transform), those tile specs will be removed from the collection.
     *
     * @param  transformSpec    identifies the transform to be concatenated to all tiles.
     *
     * @throws IllegalArgumentException
     *   if the specified transform cannot be concatenated.
     */
    public void preConcatenateTransformToAllTiles(final LeafTransformSpec transformSpec)
            throws IllegalArgumentException {

        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        for (final String tileId : tileIdToSpecMap.keySet()) {
            addTransformSpecToTile(tileId, transformSpec, PRE_CONCATENATE_LAST);
            tileSpecCount++;
            if (timer.hasIntervalPassed()) {
                LOG.info("preConcatenateTransformToAllTiles: pre-concatenated transform to {} out of {} tiles",
                         tileSpecCount, tileIdToSpecMap.size());
            }
        }

        LOG.info("preConcatenateTransformToAllTiles: pre-concatenated transform to {} tiles, elapsedSeconds={}",
                 tileSpecCount, timer.getElapsedSeconds());
    }

    /**
     * Verifies that all tile specs in this collection have the specified z value.
     *
     * @param  expectedZ  the expected z value for all tiles.
     *
     * @throws IllegalArgumentException
     *   if the z value for any tile is null or does not match the expected z value.
     */
    public void validateCollection(final Double expectedZ)
            throws IllegalArgumentException {

        Double actualZ;

        if (getTileCount() == 0) {
            throw new IllegalArgumentException("collection does not have any tiles " +
                                               "(maybe they were removed by a prior validation process)");
        }

        for (final TileSpec tileSpec : tileIdToSpecMap.values()) {

            if (tileSpec.isBoundingBoxDerivationNeeded(tileSpec.getMeshCellSize())) {
                throw new IllegalArgumentException("tile with id '" + tileSpec.getTileId() + "' is missing bounding " +
                                                   "box attributes (minX, minY, maxX, and/or maxY)");
            }

            if (expectedZ != null) {
                actualZ = tileSpec.getZ();
                if (actualZ == null) {
                    throw new IllegalArgumentException(getBadTileZValueMessage(expectedZ, tileSpec));
                } else {
                    if (Double.compare(expectedZ, actualZ) != 0) {
                        throw new IllegalArgumentException(getBadTileZValueMessage(expectedZ, tileSpec));
                    }
                }
            }

            if (tileSpecValidator != null) {
                tileSpecValidator.validate(tileSpec);
            }
        }
    }

    /**
     * Removes any tile specs identified in the provided set from this collection.
     *
     * @param  tileIdsToRemove  identifies which tile specs should be removed.
     */
    public void removeTileSpecs(final Set<String> tileIdsToRemove) {
        removeTileSpecs(tileIdsToRemove, true);
    }

    /**
     * Removes any tile specs not identified in the provided set from this collection.
     *
     * @param  tileIdsToKeep  identifies which tile specs should be retained.
     */
    public void retainTileSpecs(final Set<String> tileIdsToKeep) {
        removeTileSpecs(tileIdsToKeep, false);
    }

    /**
     * Uses this collection's tileSpecValidator to remove any invalid tile specs.
     */
    public void removeInvalidTileSpecs() {

        if (tileSpecValidator != null) {

            final ProcessTimer processTimer = new ProcessTimer(10000);
            final int numberOfSpecs = tileIdToSpecMap.size();

            int specIndex = 0;
            final Iterator<Map.Entry<String, TileSpec>> i = tileIdToSpecMap.entrySet().iterator();
            Map.Entry<String, TileSpec> entry;
            while (i.hasNext()) {
                entry = i.next();
                if (isTileInvalid(entry.getValue())) {
                    i.remove();
                }
                specIndex++;
                if (processTimer.hasIntervalPassed()) {
                    LOG.info("removeInvalidTileSpecs: processed {} out of {} specs", specIndex, numberOfSpecs);
                }
            }
        }

        removeUnreferencedTransforms();
    }

    /**
     * Removes any shared transforms that are not referenced by tile specs.
     */
    public void removeUnreferencedTransforms() {

        if (! transformIdToSpecMap.isEmpty()) {

            final Set<String> referencedTransformIds = new HashSet<>();
            for (final TileSpec tileSpec : tileIdToSpecMap.values()) {
                addReferencedTransformIds(tileSpec.getTransforms(), referencedTransformIds);
            }

            final Iterator<Map.Entry<String, TransformSpec>> i = transformIdToSpecMap.entrySet().iterator();
            String transformSpecId;
            while (i.hasNext()) {
                transformSpecId = i.next().getKey();
                if (! referencedTransformIds.contains(transformSpecId)) {
                    i.remove();
                    LOG.info("removeUnreferencedTransforms: removed '" + transformSpecId + "'");
                }
            }

        }

    }

    /**
     * @return the number opf transform specs in this collection.
     */
    @JsonIgnore
    public int getTransformCount() {
        return transformIdToSpecMap.size();
    }

    /**
     * @return the number opf tile specs in this collection.
     */
    @JsonIgnore
    public int getTileCount() {
        return tileIdToSpecMap.size();
    }

    /**
     * @return true if this collection has at least one tile spec; otherwise false.
     */
    public boolean hasTileSpecs() {
        return ! tileIdToSpecMap.isEmpty();
    }

    /**
     * Resolves referenced transform specs for all tile specs in this collection.
     *
     * @throws IllegalArgumentException
     *   if a transform spec reference cannot be resolved.
     */
    public void resolveTileSpecs()
            throws IllegalArgumentException {
        tileIdToSpecMap.values().forEach(this::resolveTileSpec);
    }

    public Map<String, Set<String>> buildSectionIdToTileIdsMap() {
        final Map<String, Set<String>> sectionIdToTileIds = new HashMap<>();
        for (final TileSpec tileSpec : getTileSpecs()) {
            final String sectionId = tileSpec.getSectionId();
            final Set<String> tileIdsForSection = sectionIdToTileIds.computeIfAbsent(sectionId,
                                                                                      sId -> new HashSet<>());
            tileIdsForSection.add(tileSpec.getTileId());
        }
        return sectionIdToTileIds;
    }

    @Override
    public String toString() {
        return "{transformCount: " + getTransformCount() +
               ", tileCount: " + getTileCount() +
               '}';
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    public static ResolvedTileSpecCollection fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    private String getBadTileZValueMessage(final double expectedZ,
                                           final TileSpec tileSpec) {
        return "all tiles must have a z value of " + expectedZ + " but tile with id '" +
               tileSpec.getTileId() + "' has a z value of " + tileSpec.getZ();
    }

    private void resolveTileSpec(final TileSpec tileSpec)
            throws IllegalArgumentException {
        final ListTransformSpec transforms = tileSpec.getTransforms();
        if (! transforms.isFullyResolved()) {
            transforms.resolveReferences(transformIdToSpecMap);
            if (! transforms.isFullyResolved()) {
                throw new IllegalArgumentException("tile " + tileSpec.getTileId() +
                                                   " requires the following transform ids " +
                                                   transforms.getUnresolvedIds());
            }
        }
    }

    private boolean isTileInvalid(final TileSpec tileSpec) {
        boolean isInvalid = false;
        try {
            tileSpecValidator.validate(tileSpec);
        } catch (final IllegalArgumentException e) {
            LOG.error(e.getMessage());
            isInvalid = true;
        }
        return isInvalid;
    }

    private void addReferencedTransformIds(final ListTransformSpec listTransformSpec,
                                           final Set<String> referencedTransformIds) {
        TransformSpec transformSpec;
        for (int i = 0; i < listTransformSpec.size(); i++) {
            transformSpec = listTransformSpec.getSpec(i);
            if (transformSpec instanceof ReferenceTransformSpec) {
                referencedTransformIds.add(((ReferenceTransformSpec) transformSpec).getRefId());
            } else if (transformSpec instanceof ListTransformSpec) {
                addReferencedTransformIds((ListTransformSpec) transformSpec, referencedTransformIds);
            }
        }
    }

    private void removeTileSpecs(final Set<String> tileIds,
                                 final boolean removeSpecsWithIds) {
        final Iterator<Map.Entry<String, TileSpec>> i = tileIdToSpecMap.entrySet().iterator();
        Map.Entry<String, TileSpec> entry;
        while (i.hasNext()) {
            entry = i.next();
            if (tileIds.contains(entry.getKey()) == removeSpecsWithIds) {
                i.remove();
            }
        }

        removeUnreferencedTransforms();
    }

    /**
     * Hack to correct Swagger spec for this model.
     * @return null always.
     */
    @SuppressWarnings("unused")
    @ApiModelProperty(name = "tileIdToSpecMap")
    public Map<String, TileSpec> getNullTileIdToSpecMap() {
        return null;
    }

    /**
     * Hack to correct Swagger spec for this model.
     * @return null always.
     */
    @SuppressWarnings("unused")
    @ApiModelProperty(name = "transformIdToSpecMap")
    public Map<String, TransformSpec> getNullTransformIdToSpecMap() {
        return null;
    }

    @JsonIgnore
    public static AffineModel2D getAffineModelForSpec(final String context,
                                                      final TransformSpec transformSpec) {
        final CoordinateTransform transform = transformSpec.buildInstance();
        final AffineModel2D affineModel;
        if (transform instanceof AffineModel2D) {
            affineModel = (AffineModel2D) transform;
        }  else if (transform instanceof Affine2D) {
            affineModel = new AffineModel2D();
            affineModel.set(((Affine2D<?>) transform).createAffine());
        } else {
            throw new IllegalArgumentException(context + " transform must implement " + Affine2D.class.getName());
        }
        return affineModel;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ResolvedTileSpecCollection.class);

    private static final JsonUtils.Helper<ResolvedTileSpecCollection> JSON_HELPER =
            new JsonUtils.Helper<>(ResolvedTileSpecCollection.class);
}
