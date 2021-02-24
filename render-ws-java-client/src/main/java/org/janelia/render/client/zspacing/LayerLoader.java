package org.janelia.render.client.zspacing;

import ij.ImagePlus;
import ij.process.FloatProcessor;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;

/**
 * Interface (and several implementations) for loading aligned layer pixels for z position correction.
 *
 * @author Eric Trautman
 */
public interface LayerLoader {

    /**
     * @return total number of slices to load.
     */
    int getNumberOfLayers();

    /**
     * @return image processor for the specified slice index.
     */
    FloatProcessor getProcessor(final int layerIndex);

    /**
     * Simple LRU cache of loaded processors for slices.
     */
    class SimpleLeastRecentlyUsedLayerCache
            implements LayerLoader {

        private final LayerLoader loader;
        private final int maxNumberOfLayersToCache;

        private final LinkedHashMap<Integer, FloatProcessor> indexToLayerProcessor;

        public SimpleLeastRecentlyUsedLayerCache(final LayerLoader loader,
                                                 final int maxNumberOfLayersToCache) {
            this.loader = loader;
            this.maxNumberOfLayersToCache = maxNumberOfLayersToCache;
            this.indexToLayerProcessor = new LinkedHashMap<>();
        }

        @Override
        public int getNumberOfLayers() {
            return loader.getNumberOfLayers();
        }

        @Override
        public FloatProcessor getProcessor(final int layerIndex) {
            return threadSafeGet(layerIndex);
        }

        private synchronized FloatProcessor threadSafeGet(final int layerIndex) {

            FloatProcessor processor = indexToLayerProcessor.remove(layerIndex);

            if (processor == null) {

                if (indexToLayerProcessor.size() >= maxNumberOfLayersToCache) {
                    final Integer leastRecentlyUsedIndex = indexToLayerProcessor.keySet()
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("cache should have at least one element"));
                    indexToLayerProcessor.remove(leastRecentlyUsedIndex);
                }

                processor = loader.getProcessor(layerIndex);
            }

            // reorder linked hash map so that most recently used is last
            indexToLayerProcessor.put(layerIndex, processor);

            return processor;
        }
    }

    class PathLayerLoader implements LayerLoader, Serializable {

        private final List<String> layerPathList;

        public PathLayerLoader(final List<String> layerPathList) {
            this.layerPathList = layerPathList;
        }

        @Override
        public int getNumberOfLayers() {
            return layerPathList.size();
        }

        @Override
        public FloatProcessor getProcessor(final int layerIndex) {
            final String layerPath = layerPathList.get(layerIndex);
            return new ImagePlus(layerPath).getProcessor().convertToFloatProcessor();
        }
    }

    class RenderLayerLoader implements LayerLoader, Serializable {

        private final String layerUrlPattern;
        private final List<Double> zList;

        private final Map<Double, List<SectionData>> zToSectionDataList;
        private final String baseDataUrl;
        private final String matchOwner;
        private final String matchCollection;

        private String debugFilePattern;

        private transient RenderDataClient matchClient;

        public RenderLayerLoader(final String layerUrlPattern,
                                 final List<SectionData> sectionDataList) {
            this.layerUrlPattern = layerUrlPattern;
            this.zList = sectionDataList.stream()
                    .map(SectionData::getZ)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            this.zToSectionDataList = null;
            this.baseDataUrl = null;
            this.matchOwner = null;
            this.matchCollection = null;
            this.debugFilePattern = null;
        }

        public RenderLayerLoader(final String layerUrlPattern,
                                 final List<SectionData> sectionDataList,
                                 final String baseDataUrl,
                                 final String matchOwner,
                                 final String matchCollection) {
            this.layerUrlPattern = layerUrlPattern;
            this.zToSectionDataList = new HashMap<>(sectionDataList.size());
            sectionDataList.forEach(sd -> {
                final List<SectionData> sdList = zToSectionDataList.computeIfAbsent(sd.getZ(),
                                                                                    f -> new ArrayList<>());
                sdList.add(sd);
            });
            this.zList = this.zToSectionDataList.keySet().stream().sorted().collect(Collectors.toList());
            this.baseDataUrl = baseDataUrl;
            this.matchOwner = matchOwner;
            this.matchCollection = matchCollection;
            this.debugFilePattern = null;
        }

        @Override
        public int getNumberOfLayers() {
            return zList.size();
        }

        @Override
        public FloatProcessor getProcessor(final int layerIndex) {

            final Double z = zList.get(layerIndex);
            final String url = String.format(layerUrlPattern, z);

            final RenderParameters renderParameters = RenderParameters.loadFromUrl(url);

            if (matchOwner != null) {
                if (matchClient == null) {
                    setMatchClient();
                }
                // TODO: fetch matches and crop render parameters
            }

            final File debugFile = debugFilePattern == null ? null : new File(String.format(debugFilePattern, z));

            final ImageProcessorWithMasks imageProcessorWithMasks =
                    Renderer.renderImageProcessorWithMasks(renderParameters,
                                                           ImageProcessorCache.DISABLED_CACHE,
                                                           debugFile);

            // TODO: make sure it is ok to drop mask here
            return imageProcessorWithMasks.ip.convertToFloatProcessor();
        }

        public Double getFirstLayerZ() {
            return zList.get(0);
        }

        public void setDebugFilePattern(final String debugFilePattern) {
            this.debugFilePattern = debugFilePattern;
        }

        private synchronized void setMatchClient() {
            if (matchClient == null) {
                this.matchClient = new RenderDataClient(baseDataUrl, matchOwner, matchCollection);
            }
        }
    }
}
