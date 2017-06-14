package org.janelia.alignment;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

import org.janelia.alignment.mipmap.MipmapSource;

/**
 * Couples a {@link MipmapSource} with a {@link CoordinateTransformList list of transforms} for rendering.
 *
 * @author Eric Trautman
 */
public class TransformableCanvas {

    private final MipmapSource source;
    private final CoordinateTransformList<CoordinateTransform> transformList;

    public TransformableCanvas(final MipmapSource source,
                               final CoordinateTransformList<CoordinateTransform> transformList) {
        this.source = source;
        this.transformList = transformList;
    }

    public MipmapSource getSource() {
        return source;
    }

    public CoordinateTransformList<CoordinateTransform> getTransformList() {
        return transformList;
    }

}
