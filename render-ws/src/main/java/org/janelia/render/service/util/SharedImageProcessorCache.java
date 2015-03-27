package org.janelia.render.service.util;

import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The image processor cache to be shared across all render web service requests.
 *
 * @author Eric Trautman
 */
public class SharedImageProcessorCache {

    private static ImageProcessorCache sharedCache;

    public static ImageProcessorCache getInstance() {
        if (sharedCache == null) {
            setSharedCache();
        }
        return sharedCache;
    }

    private static synchronized void setSharedCache() {
        if (sharedCache == null) {
            long maxCachedPixels = ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS;
            final long maxMemory = Runtime.getRuntime().maxMemory();
            if (maxMemory < Long.MAX_VALUE) {
                maxCachedPixels = maxMemory / 2;  // TODO: consider configuring max pixels instead
            }
            sharedCache = new ImageProcessorCache(maxCachedPixels, true, false);

            LOG.info("setSharedCache: exit, created {}", sharedCache);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SharedImageProcessorCache.class);
}
