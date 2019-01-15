package org.janelia.render.client.cache;

/**
 * Base methods required for managing cached canvas data.
 *
 * @author Eric Trautman
 */
public interface CachedCanvasData {

    /**
     * @return the number of kilobytes this data uses.
     */
    long getKilobytes();

    /**
     * Hook for cleaning up external resources (e.g. files) for this canvas when it gets removed from the cache.
     */
    void remove();

}