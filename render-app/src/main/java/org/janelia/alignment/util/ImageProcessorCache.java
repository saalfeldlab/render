package org.janelia.alignment.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.loader.ImageLoader.LoaderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache of {@link ImageProcessor} instances for rendering.
 * Each cache is constrained by a max pixel count parameter which should roughly correlate to max memory usage.
 * Once a cache is full, least recently used instances are removed to make room.
 * Cache instances are thread safe and do not depend upon garbage collection or independent threads to evict
 * stale entries, making the instances safer for use in application servers.
 *
 * For gory details about the cache implementation, see
 * <a href="https://code.google.com/p/guava-libraries/wiki/CachesExplained">
 *     https://code.google.com/p/guava-libraries/wiki/CachesExplained
 * </a>.
 *
 * @author Eric Trautman
 */
public class ImageProcessorCache {

    /** Cache instance that doesn't cache anything but provides the same API for loading images. */
    public static final ImageProcessorCache DISABLED_CACHE = new DisabledCache();
    
    /** Default max number of pixels is 1GB (or 160 full resolution 2500x2500 pixel tiles). */
    public static final long DEFAULT_MAX_CACHED_PIXELS = 1000 * 1000000; // 1GB

    private final long maximumNumberOfCachedKilobytes;
    private final boolean recordStats;
    private final boolean cacheOriginalsForDownSampledImages;

    private final LoadingCache<CacheKey, ImageProcessor> cache;

    /**
     * Constructor for disabled cache.
     */
    private ImageProcessorCache() {
        this.maximumNumberOfCachedKilobytes = 0;
        this.recordStats = false;
        this.cacheOriginalsForDownSampledImages = false;
        this.cache = null;
    }

    /**
     * Constructs a cache instance using the specified parameters.
     *
     * @param  maximumNumberOfCachedPixels         the maximum number of pixels to maintain in the cache.
     *                                             This should roughly correlate to the maximum amount of
     *                                             memory for the cache.
     *
     * @param  recordStats                         if true, useful tuning stats like cache hits and loads will be
     *                                             maintained (presumably at some nominal overhead cost);
     *                                             otherwise stats are not maintained.
     *
     * @param  cacheOriginalsForDownSampledImages  if true, when down sampled images are requested their source
     *                                             images will also be cached (presumably improving the speed
     *                                             of future down sampling to a different level);
     *                                             otherwise only the down sampled result images are cached.
     */
    public ImageProcessorCache(final long maximumNumberOfCachedPixels,
                               final boolean recordStats,
                               final boolean cacheOriginalsForDownSampledImages) {

        this.maximumNumberOfCachedKilobytes = maximumNumberOfCachedPixels / 1000;
        this.recordStats = recordStats;
        this.cacheOriginalsForDownSampledImages = cacheOriginalsForDownSampledImages;

        final Weigher<CacheKey, ImageProcessor> weigher =
                (key, value) -> {
                    final long kilobyteCount = ImageProcessorUtil.getKilobytes(value);
                    final int weight;
                    if (kilobyteCount < 0 || kilobyteCount > Integer.MAX_VALUE) {
                        weight = Integer.MAX_VALUE;
                        LOG.warn("{} is too large ({} kilobytes) for cache weight function, using max weight of {}",
                                 key, kilobyteCount, weight);
                    } else {
                        weight = Math.max(1, (int) kilobyteCount);
                    }
                    return weight;
                };

        final CacheLoader<CacheKey, ImageProcessor> loader =
                new CacheLoader<CacheKey, ImageProcessor>() {

                    @Override
                    public ImageProcessor load(final CacheKey key) {
                        return loadImageProcessor(key.getUri(),
                                                  key.getDownSampleLevels(),
                                                  key.isMask(),
                                                  key.getImageLoader());
                    }
                };


        if (recordStats) {
            cache = CacheBuilder.newBuilder()
                    .maximumWeight(maximumNumberOfCachedKilobytes)
                    .weigher(weigher)
                    .recordStats()
                    .build(loader);
        } else {
            cache = CacheBuilder.newBuilder()
                    .maximumWeight(maximumNumberOfCachedKilobytes)
                    .weigher(weigher)
                    .build(loader);
        }

    }

    /**
     * @param  url               url for the image.
     *
     * @param  downSampleLevels  number of levels to further down sample the image.
     *                           Negative values are considered the same as zero.
     *
     * @param  isMask            indicates whether the image is a mask.
     *
     * @param  convertTo16Bit    indicates whether the loaded image processor should be converted to 16-bit.
     *
     * @param  loaderType        loader for image.
     *
     * @param  imageSliceNumber  (optional) slice number for 3D sources.
     *
     * @return a duplicate instance of the cached image processor for the specified url string.
     *         If the source processor is not already cached, it will be loaded into the cache.
     *         The duplicate instance is returned because the processors are mutable and the cached
     *         instance needs to remain unaltered for future use.
     *
     * @throws IllegalArgumentException
     *   if the image cannot be loaded.
     */
    public ImageProcessor get(final String url,
                              final int downSampleLevels,
                              final boolean isMask,
                              final boolean convertTo16Bit,
                              final LoaderType loaderType,
                              final Integer imageSliceNumber)
            throws IllegalArgumentException {

        final ImageLoader imageLoader =  ImageLoader.build(loaderType, imageSliceNumber);

        final CacheKey key = new CacheKey(url, downSampleLevels, isMask, imageLoader);
        final ImageProcessor cachedImageProcessor;
        try {
            cachedImageProcessor = cache.get(key);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to retrieve " + key + " from cache", t);
        }

        final ImageProcessor duplicateProcessor;
        if (convertTo16Bit && (cachedImageProcessor.getBitDepth() == 8)) {
            // Force images to 16-bit, to allow for testing of mixed 8-bit and 16-bit mipmap levels.
            duplicateProcessor = cachedImageProcessor.convertToShort(false);
            duplicateProcessor.multiply(256.0);
        } else {
            duplicateProcessor = cachedImageProcessor.duplicate();
        }

        return duplicateProcessor;
    }

    /**
     * @return the number of entries currently in this cache.
     */
    public long size() {
        return cache == null ? 0 : cache.size();
    }

    /**
     * Discards all entries in the cache.
     */
    public void invalidateAll() {
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * @return a current snapshot of this cache's cumulative statistics
     *         (will be all zeros if stat recording is not enabled for this cache).
     */
    public CacheStats getStats() {
        return cache == null ? EMPTY_STATS : cache.stats();
    }

    @Override
    public String toString() {
        return "{numberOfEntries: " + size() +
               ", maximumNumberOfCachedKilobytes: " + maximumNumberOfCachedKilobytes +
               ", recordStats: " + recordStats +
               ", cacheOriginalsForDownSampledImages: " + cacheOriginalsForDownSampledImages +
               '}';
    }

    /**
     * The core method used to load image processor instances that is called when cache misses occur.
     *
     * @param  urlString               url for the image.
     * @param  downSampleLevels  number of levels to further down sample the image.
     * @param  isMask            indicates whether this image is a mask.
     * @param  imageLoader       loader to use.
     *
     * @return a newly loaded image processor to be cached.
     *
     * @throws IllegalArgumentException
     *   if the image cannot be loaded.
     */
    protected ImageProcessor loadImageProcessor(final String urlString,
                                                final int downSampleLevels,
                                                final boolean isMask,
                                                final ImageLoader imageLoader)
            throws IllegalArgumentException {

        if (LOG.isDebugEnabled()) {
            LOG.debug("loadImageProcessor: entry, urlString={}, downSampleLevels={}, imageLoaderClass={}",
                      urlString, downSampleLevels, imageLoader.getClass().getSimpleName());
        }

        ImageProcessor imageProcessor = null;

        // if we need to down sample, see if source image is already cached before trying to load it
        if (downSampleLevels > 0) {
            imageProcessor = cache.getIfPresent(new CacheKey(urlString, 0, isMask, imageLoader));
        }

        // load the image as needed
        if (imageProcessor == null) {

            imageProcessor = imageLoader.load(urlString);

            // force masks to always be ByteProcessor instances
            if (isMask && (! (imageProcessor instanceof ByteProcessor))) {
                imageProcessor = imageProcessor.convertToByteProcessor();
            }

            // if we're going to down sample and we're supposed to cache originals, do so here
            if (cacheOriginalsForDownSampledImages && (downSampleLevels > 0)) {

                if (LOG.isDebugEnabled()) {
                    LOG.debug("loadImageProcessor: caching level 0 for {}", urlString);
                }

                cache.put(new CacheKey(urlString, 0, isMask, imageLoader), imageProcessor);
            }

        }

        // down sample the image as needed
        if (downSampleLevels > 0) {
            // NOTE: The down sample methods return a safe copy and leave the source imageProcessor unmodified,
            //       so we don't need to duplicate a cached source instance before down sampling.
            imageProcessor = Downsampler.downsampleImageProcessor(imageProcessor,
                                                                  downSampleLevels);
        }

        return imageProcessor;
    }

    /**
     * Key that combines an image's url with its down sample levels.
     */
    protected static class CacheKey {

        private final String url;
        private final int downSampleLevels;
        private final boolean isMask;
        private final ImageLoader imageLoader;

        CacheKey(final String url,
                 final int downSampleLevels,
                 final boolean isMask,
                 final ImageLoader imageLoader) {

            this.url = url;
            this.downSampleLevels = Math.max(downSampleLevels, 0);
            this.isMask = isMask;
            this.imageLoader = imageLoader;
        }

        public String getUri() {
            return url;
        }

        int getDownSampleLevels() {
            return downSampleLevels;
        }

        public boolean isMask() {
            return isMask;
        }

        public ImageLoader getImageLoader() {
            return imageLoader;
        }

        @Override
        public String toString() {
            return "{url: '" + url + "', downSampleLevels: " + downSampleLevels + ", isMask: " + isMask +
                   ", imageLoader: " + imageLoader + '}';
        }

        @Override
        public boolean equals(final Object o) {
            boolean result = true;
            if (this != o) {
                if (o instanceof CacheKey) {
                    final CacheKey that = (CacheKey) o;
                    result = this.url.equals(that.url) &&
                             (this.downSampleLevels == that.downSampleLevels) &&
                             (this.imageLoader.hasSame3DContext(that.imageLoader));
                } else {
                    result = false;
                }
            }
            return result;
        }

        @Override
        public int hashCode() {
            int result = url.hashCode();
            result = 31 * result + downSampleLevels;
            return result;
        }
    }

    /** Cache that doesn't cache anything but provides the same API for loading images. */
    private static class DisabledCache extends ImageProcessorCache {

        public DisabledCache() {
            super();
        }

        @Override
        public ImageProcessor get(final String url,
                                  final int downSampleLevels,
                                  final boolean isMask,
                                  final boolean convertTo16Bit,
                                  final LoaderType loaderType,
                                  final Integer imageSliceNumber) {

            if (LOG.isDebugEnabled()) {
                LOG.debug("uncachedGet: entry, urlString={}, downSampleLevels={}", url, downSampleLevels);
            }

            final ImageLoader imageLoader =  ImageLoader.build(loaderType, imageSliceNumber);
            ImageProcessor imageProcessor = imageLoader.load(url);

            // down sample the image as needed
            if (downSampleLevels > 0) {
                imageProcessor = Downsampler.downsampleImageProcessor(imageProcessor,
                                                                      downSampleLevels);
            }

            return imageProcessor;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImageProcessorCache.class);

    private static final CacheStats EMPTY_STATS = new CacheStats(0,0,
                                                                 0,0,
                                                                 0,0);
}
