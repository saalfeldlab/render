package org.janelia.alignment.match.cache;

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

    private final String dataLoaderId;

    /**
     * @param  dataLoaderId    unique identifier for data loader implementation.
     */
    CanvasDataLoader(final String dataLoaderId) {
        this.dataLoaderId = dataLoaderId;
    }

    public String getDataLoaderId() {
        return dataLoaderId;
    }
}
