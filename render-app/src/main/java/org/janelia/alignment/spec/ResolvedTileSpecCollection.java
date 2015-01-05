package org.janelia.alignment.spec;

import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A collection of tile specifications that also includes all referenced transform specifications,
 * allowing the tile specifications to be fully resolved.
 *
 * @author Eric Trautman
 */
public class ResolvedTileSpecCollection {

    private Map<String, TransformSpec> transformIdToSpecMap;
    private Map<String, TileSpec> tileIdToSpecMap;

    @SuppressWarnings("UnusedDeclaration")
    public ResolvedTileSpecCollection() {
        this(new ArrayList<TransformSpec>(), new ArrayList<TileSpec>());
    }

    public ResolvedTileSpecCollection(final Collection<TransformSpec> transformSpecs,
                                      final Collection<TileSpec> tileSpecs) {

        this.transformIdToSpecMap = new HashMap<String, TransformSpec>(transformSpecs.size() * 2);
        this.tileIdToSpecMap = new HashMap<String, TileSpec>(tileSpecs.size() * 2);

        for (TransformSpec transformSpec : transformSpecs) {
            addTransformSpecToCollection(transformSpec);
        }

        for (TileSpec tileSpec : tileSpecs) {
            addTileSpecToCollection(tileSpec);
        }
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
        tileSpec.deriveBoundingBox(true);
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
                LOG.debug("addReferenceTransformToAllTiles: added transform to {} out of {} tiles",
                          tileSpecCount, tileIdToSpecMap.size());
            }
        }

        LOG.debug("addReferenceTransformToAllTiles: added transform to {} tiles, elapsedSeconds={}",
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

    private String getBadTileZValueMessage(double expectedZ,
                                           TileSpec tileSpec) {
        return "all tiles must have a z value of " + expectedZ + " but tile " +
               tileSpec.getTileId() + " has a z value of " + tileSpec.getZ();
    }

    private void resolveTileSpecs()
            throws IllegalStateException {
        for (TileSpec tileSpec : tileIdToSpecMap.values()) {
            resolveTileSpec(tileSpec);
        }
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

    private static final Logger LOG = LoggerFactory.getLogger(ResolvedTileSpecCollection.class);
}
