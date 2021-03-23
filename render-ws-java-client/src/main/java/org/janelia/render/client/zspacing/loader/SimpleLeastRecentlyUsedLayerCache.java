package org.janelia.render.client.zspacing.loader;

import java.util.LinkedHashMap;

/**
 * Simple LRU cache of loaded processors for slices.
 *
 * @author Eric Trautman
 */
public class SimpleLeastRecentlyUsedLayerCache implements LayerLoader {

    private final LayerLoader loader;
    private final int maxNumberOfLayersToCache;

    private final LinkedHashMap<Integer, FloatProcessors> indexToLayerProcessors;

    public SimpleLeastRecentlyUsedLayerCache(final LayerLoader loader,
                                             final int maxNumberOfLayersToCache) {
        this.loader = loader;
        this.maxNumberOfLayersToCache = maxNumberOfLayersToCache;
        this.indexToLayerProcessors = new LinkedHashMap<>();
    }

    @Override
    public int getNumberOfLayers() {
        return loader.getNumberOfLayers();
    }

    @Override
    public FloatProcessors getProcessors(final int layerIndex) {
        return threadSafeGet(layerIndex);
    }

    private synchronized FloatProcessors threadSafeGet(final int layerIndex) {

        FloatProcessors processors = indexToLayerProcessors.remove(layerIndex);

        if (processors == null) {

            if (indexToLayerProcessors.size() >= maxNumberOfLayersToCache) {
                final Integer leastRecentlyUsedIndex = indexToLayerProcessors.keySet()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("cache should have at least one element"));
                indexToLayerProcessors.remove(leastRecentlyUsedIndex);
            }

            processors = loader.getProcessors(layerIndex);
        }

        // reorder linked hash map so that most recently used is last
        indexToLayerProcessors.put(layerIndex, processors);

        return processors;
    }

}
