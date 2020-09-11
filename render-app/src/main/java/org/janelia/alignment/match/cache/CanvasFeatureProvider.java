package org.janelia.alignment.match.cache;

import org.janelia.alignment.match.CanvasIdWithRenderContext;

/**
 * Base methods required for providing canvas feature data.
 *
 * @author Eric Trautman
 */
public interface CanvasFeatureProvider {

    /**
     * @return feature data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be built or found.
     */
    CachedCanvasFeatures getCanvasFeatures(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException;

}