package org.janelia.render.client.intensityadjust;

import java.util.Map;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
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

    public String getTileImageUrl() {
        final Map.Entry<Integer, ImageAndMask> mipmapEntry = tileSpec.getFirstMipmapEntry();
        final ImageAndMask imageAndMask = mipmapEntry.getValue();
        return imageAndMask.getImageUrl();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final MinimalTileSpecWrapper that = (MinimalTileSpecWrapper) o;

        return tileSpec.getTileId().equals(that.tileSpec.getTileId());
    }

    @Override
    public int hashCode() {
        return tileSpec.getTileId().hashCode();
    }
}
