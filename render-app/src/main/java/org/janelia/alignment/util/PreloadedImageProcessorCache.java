package org.janelia.alignment.util;

import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.Map;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.loader.ImageLoader.LoaderType;

public class PreloadedImageProcessorCache extends ImageProcessorCache {

    private final Map<String, ImageProcessor> preloadedUrlToProcessorMap;

    public PreloadedImageProcessorCache(final long maximumNumberOfCachedPixels,
                                        final boolean recordStats,
                                        final boolean cacheOriginalsForDownSampledImages) {
        super(maximumNumberOfCachedPixels, recordStats, cacheOriginalsForDownSampledImages);
        this.preloadedUrlToProcessorMap = new HashMap<>();
    }

    public void put(final String url,
                    final ImageProcessor imageProcessor) {
        preloadedUrlToProcessorMap.put(url, imageProcessor);
    }

    public ImageProcessor get(final String url,
                              final int downSampleLevels,
                              final boolean isMask,
                              final boolean convertTo16Bit,
                              final LoaderType loaderType,
                              final Integer imageSliceNumber)
            throws IllegalArgumentException {

        ImageProcessor imageProcessor = preloadedUrlToProcessorMap.get(url);

        if (imageProcessor == null) {
            imageProcessor = super.get(url, downSampleLevels, isMask, convertTo16Bit, loaderType, imageSliceNumber);
        } else {
            // TODO: cache downsampled processors if this ever gets used for something besides full-scale rendering
            if (downSampleLevels > 0) {
                imageProcessor = Downsampler.downsampleImageProcessor(imageProcessor,
                                                                      downSampleLevels);
            }
        }

        return imageProcessor;
    }

    /**
     * @return the number of entries currently in this cache.
     */
    public long size() {
        return super.size() + preloadedUrlToProcessorMap.size();
    }

}
