package org.janelia.alignment.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import mpicbg.trakem2.util.Downsampler;

import org.janelia.alignment.protocol.s3.S3Opener;
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
    public static final ImageProcessorCache DISABLED_CACHE = new ImageProcessorCache(0, false, false);
    
    /** Default max number of pixels is 1GB (or 160 full resolution 2500x2500 pixel tiles). */
    public static final long DEFAULT_MAX_CACHED_PIXELS = 1000 * 1000000; // 1GB

    private final long maximumNumberOfCachedKilobytes;
    private final boolean recordStats;
    private final boolean cacheOriginalsForDownSampledImages;

    private final LoadingCache<CacheKey, ImageProcessor> cache;

    /**
     * Constructs an instance with default parameters.
     */
    public ImageProcessorCache() {
        this(DEFAULT_MAX_CACHED_PIXELS, true, true);
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
                    final long bitCount = ((long) value.getPixelCount()) * value.getBitDepth();
                    final long kilobyteCount = bitCount / 8000L;
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
                        ImageProcessor imageProcessor = null;
                        imageProcessor = loadImageProcessor(key.getUri(), key.getDownSampleLevels(), key.isMask(),key.isConvertTo16Bit());
                        return imageProcessor;
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
                              final boolean convertTo16Bit)
            throws IllegalArgumentException {

        final CacheKey key = new CacheKey(url, downSampleLevels, isMask,convertTo16Bit);
        final ImageProcessor imageProcessor;
        try {
            imageProcessor = cache.get(key);
        } catch (final Throwable t) {
            throw new IllegalArgumentException("failed to retrieve " + key + " from cache", t);
        }
        return imageProcessor.duplicate();
    }

    /**
     * @return the number of entries currently in this cache.
     */
    public long size() {
        return cache.size();
    }

    /**
     * Discards all entries in the cache.
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /**
     * @return a current snapshot of this cache's cumulative statistics
     *         (will be all zeros if stat recording is not enabled for this cache).
     */
    public CacheStats getStats() {
        return cache.stats();
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
     * @param  url               url for the image.
     * @param  downSampleLevels  number of levels to further down sample the image.
     * @param  isMask            indicates whether this image is a mask.
     * @param  convertTo16Bit    indicates whether the loaded image processor should be converted to 16-bit.
     *
     * @return a newly loaded (non-cached) image processor.
     *
     * @throws IllegalArgumentException
     *   if the image cannot be loaded.
     */
    public static ImageProcessor getNonCachedImage(final String url,
                                                   final int downSampleLevels,
                                                   final boolean isMask,
                                                   final boolean convertTo16Bit)
            throws IllegalArgumentException {
        return DISABLED_CACHE.loadImageProcessor(url, downSampleLevels, isMask, convertTo16Bit);
    }

    /**
     * The core method used to load image processor instances that is called when cache misses occur.
     *
     * @param  url               url for the image.
     * @param  downSampleLevels  number of levels to further down sample the image.
     * @param  isMask            indicates whether this image is a mask.
     * @param  convertTo16Bit    indicates whether the loaded image processor should be converted to 16-bit.
     *
     * @return a newly loaded image processor to be cached.
     *
     * @throws IllegalArgumentException
     *   if the image cannot be loaded.
     */
    protected ImageProcessor loadImageProcessor(final String url,
                                                final int downSampleLevels,
                                                final boolean isMask,
                                                final boolean convertTo16Bit)
            throws IllegalArgumentException {

        if (LOG.isDebugEnabled()) {
            LOG.debug("loadImageProcessor: entry, url={}, downSampleLevels={}, convertTo16Bit={}", url, downSampleLevels,convertTo16Bit);
        }

        ImageProcessor imageProcessor = null;

        // if we need to down sample, see if source image is already cached before trying to load it
        if (downSampleLevels > 0) {
            imageProcessor = cache.getIfPresent(new CacheKey(url, 0, isMask,convertTo16Bit));
        }

        // load the image as needed
        if (imageProcessor == null) {

            // TODO: use Bio Formats to load strange formats

            // openers keep state about the file being opened, so we need to create a new opener for each load
            final Opener opener = new S3Opener();
            opener.setSilentMode(true);

            final ImagePlus imagePlus = opener.openURL(url);
            if (imagePlus == null) {
                throw new IllegalArgumentException("failed to create imagePlus instance for '" + url + "'");
            }

            imageProcessor = imagePlus.getProcessor();

            // Force images to 16-bit, to allow for testing of mixed 8-bit and 16-bit mipmap levels.
            if ((! isMask) && (imageProcessor.getBitDepth() == 8) && convertTo16Bit) {
                imageProcessor = imageProcessor.convertToShort(false);
                imageProcessor.multiply(256.0);
            }

            // if we're going to down sample and we're supposed to cache originals, do so here
            if (cacheOriginalsForDownSampledImages && (downSampleLevels > 0)) {

                if (LOG.isDebugEnabled()) {
                    LOG.debug("loadImageProcessor: caching level 0 for {}", url);
                }

                cache.put(new CacheKey(url, 0, isMask,convertTo16Bit), imageProcessor);
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
    private class CacheKey {

        private final String url;
        private final int downSampleLevels;
        private final boolean isMask;
        private final boolean convertTo16Bit;

        CacheKey(final String url,
                 final int downSampleLevels,
                 final boolean isMask,
                 final boolean convertTo16Bit) {

            this.url = url;

            if (downSampleLevels < 0) {
                this.downSampleLevels = 0;
            } else {
                this.downSampleLevels = downSampleLevels;
            }

            this.isMask = isMask;
            this.convertTo16Bit = convertTo16Bit;
        }

        public String getUri() {
            return url;
        }
        boolean isConvertTo16Bit(){
            return convertTo16Bit;
	    }
        int getDownSampleLevels() {
            return downSampleLevels;
        }

        public boolean isMask() {
            return isMask;
        }

        @Override
        public String toString() {
            return "{url: '" + url + "', downSampleLevels: " + downSampleLevels + ", isMask: " + isMask + ", convertTo16Bit:" + convertTo16Bit +  '}';
        }

        @Override
        public boolean equals(final Object o) {
            boolean result = true;
            if (this != o) {
                if (o instanceof CacheKey) {
                    final CacheKey that = (CacheKey) o;
                    result = this.url.equals(that.url) &&
                             (this.downSampleLevels == that.downSampleLevels) &&
                             (this.convertTo16Bit == that.convertTo16Bit);
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

    private static final Logger LOG = LoggerFactory.getLogger(ImageProcessorCache.class);

}
