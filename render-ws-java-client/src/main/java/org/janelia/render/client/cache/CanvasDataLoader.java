package org.janelia.render.client.cache;

import com.google.common.cache.CacheLoader;

import java.io.Serializable;

import org.janelia.alignment.match.CanvasIdWithRenderContext;

/**
 * Loader implementations create data that is missing from the cache.
 *
 * @author Eric Trautman
 */
public abstract class CanvasDataLoader
        extends CacheLoader<CanvasIdWithRenderContext, CachedCanvasData> implements Serializable {

    private final Class<? extends CachedCanvasData> dataClass;

    /**
     * @param  dataClass    class of specific data loader implementation.
     */
    CanvasDataLoader(final Class<? extends CachedCanvasData> dataClass) {
        this.dataClass = dataClass;
    }

    Class<? extends CachedCanvasData> getDataClass() {
        return dataClass;
    }
}
