package org.janelia.alignment.match.cache;

import org.janelia.alignment.match.CanvasIdWithRenderContext;

/**
 * Base methods required for providing canvas peak data.
 *
 * @author Eric Trautman
 */
public interface CanvasPeakProvider {

    /**
     * @return peak data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be built or found.
     */
    CachedCanvasPeaks getCanvasPeaks(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException;

}