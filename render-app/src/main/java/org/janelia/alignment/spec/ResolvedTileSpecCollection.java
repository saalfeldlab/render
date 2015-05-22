package org.janelia.alignment.spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A collection of tile specifications that also includes all referenced transform specifications,
 * allowing the tile specifications to be fully resolved.
 *
 * @author Eric Trautman
 */
public class ResolvedTileSpecCollection {

    private String stackName;
    private Map<String, TransformSpec> transformIdToSpecMap;
    private Map<String, TileSpec> tileIdToSpecMap;

    @SuppressWarnings("UnusedDeclaration")
    public ResolvedTileSpecCollection() {
        this(null, new ArrayList<TransformSpec>(), new ArrayList<TileSpec>());
    }

    public ResolvedTileSpecCollection(final String stackName,
                                      final Collection<TransformSpec> transformSpecs,
                                      final Collection<TileSpec> tileSpecs) {

        this.stackName = stackName;
        this.transformIdToSpecMap = new HashMap<>(transformSpecs.size() * 2);
        this.tileIdToSpecMap = new HashMap<>(tileSpecs.size() * 2);

        for (TransformSpec transformSpec : transformSpecs) {
            addTransformSpecToCollection(transformSpec);
        }

        for (TileSpec tileSpec : tileSpecs) {
            addTileSpecToCollection(tileSpec);
        }
    }

    public TileSpec getTileSpec(String tileId) {
        return tileIdToSpecMap.get(tileId);
    }

    public Collection<TileSpec> getTileSpecs()
            throws IllegalStateException {
        resolveTileSpecs();
        return tileIdToSpecMap.values();
    }

    public void addTileSpecToCollection(TileSpec tileSpec)
            throws IllegalStateException {
        resolveTileSpec(tileSpec);
        tileIdToSpecMap.put(tileSpec.getTileId(), tileSpec);
    }

    public Collection<TransformSpec> getTransformSpecs() {
        return transformIdToSpecMap.values();
    }

    public void addTransformSpecToCollection(TransformSpec transformSpec) {
        transformIdToSpecMap.put(transformSpec.getId(), transformSpec);
    }

    public void addTransformSpecToTile(String tileId,
                                       TransformSpec transformSpec,
                                       boolean replaceLast) throws IllegalArgumentException {

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

        if (replaceLast) {
            tileSpec.removeLastTransformSpec();
        }

        tileSpec.addTransformSpecs(Arrays.asList(transformSpec));

        // addition of new transform spec obsolesces the previously resolved coordinate transform instance,
        // so we need to re-resolve the tile before re-deriving the bounding box
        resolveTileSpec(tileSpec);

        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        removeTileIfInvalid(tileSpec);
    }

    public void addReferenceTransformToAllTiles(String transformId)
            throws IllegalArgumentException {

        final TransformSpec transformSpec = transformIdToSpecMap.get(transformId);
        if (transformSpec == null) {
            throw new IllegalArgumentException("transform " + transformId + " cannot be found");
        }

        final TransformSpec referenceTransformSpec = new ReferenceTransformSpec(transformId);

        final ProcessTimer timer = new ProcessTimer();
        int tileSpecCount = 0;
        for (String tileId : tileIdToSpecMap.keySet()) {
            addTransformSpecToTile(tileId, referenceTransformSpec, false);
            tileSpecCount++;
            if (timer.hasIntervalPassed()) {
                LOG.info("addReferenceTransformToAllTiles: added transform to {} out of {} tiles",
                         tileSpecCount, tileIdToSpecMap.size());
            }
        }

        LOG.info("addReferenceTransformToAllTiles: added transform to {} tiles, elapsedSeconds={}",
                 tileSpecCount, timer.getElapsedSeconds());
    }

    public void verifyAllTileSpecsHaveZValue(double expectedZ) {
        Double actualZ;
        for (TileSpec tileSpec : tileIdToSpecMap.values()) {
            actualZ = tileSpec.getZ();
            if (actualZ == null) {
                throw new IllegalStateException(getBadTileZValueMessage(expectedZ, tileSpec));
            } else {
                if (Double.compare(expectedZ, actualZ) != 0) {
                    throw new IllegalStateException(getBadTileZValueMessage(expectedZ, tileSpec));
                }
            }
        }
    }

    public void filterSpecs(Set<String> tileIdsToKeep) {
        final Iterator<Map.Entry<String, TileSpec>> i = tileIdToSpecMap.entrySet().iterator();
        Map.Entry<String, TileSpec> entry;
        while (i.hasNext()) {
            entry = i.next();
            if (! tileIdsToKeep.contains(entry.getKey())) {
                i.remove();
            }
        }

        // TODO: remove any unreferenced transforms
    }

    public int getTransformCount() {
        return transformIdToSpecMap.size();
    }

    public int getTileCount() {
        return tileIdToSpecMap.size();
    }

    public boolean hasTileSpecs() {
        return tileIdToSpecMap.size() > 0;
    }

    public void resolveTileSpecs()
            throws IllegalStateException {
        for (TileSpec tileSpec : tileIdToSpecMap.values()) {
            resolveTileSpec(tileSpec);
        }
    }

    @Override
    public String toString() {
        return "{stackName: '" + stackName +
               ", transformCount: " + getTransformCount() +
               ", tileCount: " + getTileCount() +
               '}';
    }

    private String getBadTileZValueMessage(double expectedZ,
                                           TileSpec tileSpec) {
        return "all tiles must have a z value of " + expectedZ + " but tile " +
               tileSpec.getTileId() + " has a z value of " + tileSpec.getZ();
    }

    private void resolveTileSpec(TileSpec tileSpec)
            throws IllegalStateException {
        final ListTransformSpec transforms = tileSpec.getTransforms();
        if (! transforms.isFullyResolved()) {
            transforms.resolveReferences(transformIdToSpecMap);
            if (! transforms.isFullyResolved()) {
                throw new IllegalStateException("tile " + tileSpec.getTileId() +
                                                " requires the following transform ids " +
                                                transforms.getUnresolvedIds());
            }
        }
    }

    private void removeTileIfInvalid(TileSpec tileSpec) {

        String errorMessage = null;

        // TODO: confirm bounding box constraints (or parameter-ize them)
        final double minCoordinate = -400;
        final double maxCoordinate = 300000;
        final double minSize = 500;
        final double maxSize = 5000;

        if (tileSpec.getMinX() < minCoordinate) {
            errorMessage = "invalid minX of " + tileSpec.getMinX();
        } else if (tileSpec.getMinY() < minCoordinate) {
            errorMessage = "invalid minY of " + tileSpec.getMinY();
        } else if (tileSpec.getMaxX() > maxCoordinate) {
            errorMessage = "invalid maxX of " + tileSpec.getMaxX();
        } else if (tileSpec.getMaxY() > maxCoordinate) {
            errorMessage = "invalid maxY of " + tileSpec.getMaxY();
        } else {
            final double width = tileSpec.getMaxX() - tileSpec.getMinX();
            final double height = tileSpec.getMaxY() - tileSpec.getMinY();
            if ((width < minSize) || (width > maxSize)) {
                errorMessage = "invalid width of " + width;
            } else if ((height < minSize) || (height > maxSize)) {
                errorMessage = "invalid height of " + height;
            }
        }

        if (errorMessage != null) {
            LOG.error(errorMessage + " derived for tileId '" + tileSpec.getTileId() +
                      "', bounds are [" + tileSpec.getMinX() + ", " + tileSpec.getMinY() + ", " + tileSpec.getMaxX() +
                      ", " + tileSpec.getMaxY() + "] with transforms " +
                      tileSpec.getTransforms().toJson().replace('\n', ' '));
            tileIdToSpecMap.remove(tileSpec.getTileId());
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ResolvedTileSpecCollection.class);
}
