package org.janelia.render.client.intensityadjust;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.solver.MinimalTileSpec;

public class MinimalTileSpecWrapper extends MinimalTileSpec {

    private final TileSpec tileSpec;

    public MinimalTileSpecWrapper(final TileSpec tileSpec) {
        super(tileSpec);
        this.tileSpec = tileSpec;
    }

    public TileSpec getTileSpec() {
        return tileSpec;
    }

    public int transformCount() {
        return tileSpec.getTransforms().size();
    }

    public CoordinateTransformList<CoordinateTransform> getTransformList() {
        return tileSpec.getTransformList();
    }
}
