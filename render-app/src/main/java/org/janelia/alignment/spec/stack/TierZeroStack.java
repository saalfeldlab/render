package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;

/**
 * A HierarchicalStack for the inital "rough alignment" tier.
 * Unlike traditional hierarchical stacks which have the same bounds for all layers,
 * tier zero stacks have different bounds for each layer.
 *
 * @author Eric Trautman
 */
public class TierZeroStack extends HierarchicalStack {

    // NOTE: this map will be dropped during serialization, assuming we won't need to use it remotely
    @JsonIgnore
    private transient final Map<Double, Bounds> zToBoundsMap;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    protected TierZeroStack() {
        this(null, null, null, null);
    }

    /**
     * Constructs tier zero stack metadata.
     *
     * @param  montageTilesStackId   identifies the source stack with intra-layer aligned "montage" tiles.
     * @param  scale                 scale for rendering layers in this stack (and tier).
     * @param  zToBoundsMap          bounds for each montage layer.
     */
    public TierZeroStack(final StackId montageTilesStackId,
                         final Double scale,
                         final Bounds fullScaleBounds,
                         final Map<Double, Bounds> zToBoundsMap) {
        super(montageTilesStackId, 0, 0, 0, 1, 1, scale, fullScaleBounds);
        this.zToBoundsMap = zToBoundsMap;
    }

    @JsonIgnore
    @Override
    public String getTileIdForZ(final double z) {
        final Bounds layerBounds = zToBoundsMap.get(z);
        return String.format("z_%2.1f_box_%d_%d_%d_%d_%f",
                             z,
                             floor(layerBounds.getMinX()), floor(layerBounds.getMinY()),
                             ceil(layerBounds.getDeltaX()), ceil(layerBounds.getDeltaY()),
                             getScale());
    }

    @JsonIgnore
    @Override
    public void setTileSpecBounds(final TileSpec tileSpec) {
        setTileSpecBounds(tileSpec, getScale(), zToBoundsMap.get(tileSpec.getZ()));
    }

    @JsonIgnore
    @Override
    public String getBoxPathForZ(final double z) {
        final StackId montageTilesStackId = getParentTierStackId();
        final Bounds layerBounds = zToBoundsMap.get(z);
        return "/owner/" + montageTilesStackId.getOwner() + "/project/" + montageTilesStackId.getProject() +
               "/stack/" + montageTilesStackId.getStack() + "/z/" + z + "/box/" +
               floor(layerBounds.getMinX()) + ',' + floor(layerBounds.getMinY()) + ',' +
               ceil(layerBounds.getDeltaX()) + ',' + ceil(layerBounds.getDeltaY()) + ',' + getScale();
    }

}
