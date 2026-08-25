package org.janelia.alignment;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;

import org.janelia.alignment.mipmap.MipmapSource;

/**
 * Couples a {@link MipmapSource} with a {@link CoordinateTransformList list of transforms} for rendering.
 *
 * @author Eric Trautman
 */
public record TransformableCanvas(MipmapSource source, CoordinateTransformList<CoordinateTransform> transformList) {}
