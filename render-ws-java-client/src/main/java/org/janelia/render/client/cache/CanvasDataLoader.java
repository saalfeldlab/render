package org.janelia.render.client.cache;

import com.google.common.cache.CacheLoader;

import java.io.Serializable;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;

/**
 * Loader implementations create data that is missing from the cache.
 *
 * This base class provides a mechanism ({@link #getRenderParametersUrl(CanvasId)})
 * to derive canvas specific render parameters URLs needed to build missing data.
 *
 * @author Eric Trautman
 */
public abstract class CanvasDataLoader
        extends CacheLoader<CanvasId, CachedCanvasData> implements Serializable {

    private final CanvasRenderParametersUrlTemplate urlTemplate;
    private final Class dataClass;

    /**
     * @param  urlTemplate  template for deriving render parameters URL for each canvas.
     * @param  dataClass    class of specific data loader implementation.
     */
    CanvasDataLoader(final CanvasRenderParametersUrlTemplate urlTemplate,
                     final Class dataClass) {
        this.urlTemplate = urlTemplate;
        this.dataClass = dataClass;
    }

    Class getDataClass() {
        return dataClass;
    }

    public Integer getClipWidth() {
        return urlTemplate.getClipWidth();
    }

    public Integer getClipHeight() {
        return urlTemplate.getClipHeight();
    }

    /**
     * @return the render parameters URL for the specified canvas.
     */
    public String getRenderParametersUrl(final CanvasId canvasId) {
        return urlTemplate.getRenderParametersUrl(canvasId);
    }

    RenderParameters getRenderParameters(final CanvasId canvasId)
            throws IllegalArgumentException {
        return urlTemplate.getRenderParameters(canvasId);
    }
}
