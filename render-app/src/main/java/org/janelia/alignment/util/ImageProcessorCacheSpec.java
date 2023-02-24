package org.janelia.alignment.util;

import java.io.Serializable;

/**
 *  Serializable specification of ImageProcessorCache parameters that can be broadcast to Spark executors
 *  allowing each one to construct and share a cache across each task assigned to the executor.
 *
 * @author Eric Trautman
 */
public class ImageProcessorCacheSpec
        implements Serializable {
    private final long maximumNumberOfCachedKilobytes;
    private final boolean recordStats;
    private final boolean cacheOriginalsForDownSampledImages;

    private transient ImageProcessorCache instance;

    public ImageProcessorCacheSpec(final long maximumNumberOfCachedKilobytes,
                                   final boolean recordStats,
                                   final boolean cacheOriginalsForDownSampledImages) {
        this.maximumNumberOfCachedKilobytes = maximumNumberOfCachedKilobytes;
        this.recordStats = recordStats;
        this.cacheOriginalsForDownSampledImages = cacheOriginalsForDownSampledImages;
        this.instance = null;
    }

    public ImageProcessorCache getSharableInstance() {
        if (this.instance == null) {
            buildInstance();
        }
        return instance;
    }

    private synchronized void buildInstance() {
        if (instance == null) {
            instance = new ImageProcessorCache(maximumNumberOfCachedKilobytes,
                                               recordStats,
                                               cacheOriginalsForDownSampledImages);
        }
    }
}
