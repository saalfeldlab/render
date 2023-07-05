package org.janelia.alignment.match.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalListeners;
import com.google.common.cache.Weigher;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class manages a singleton cache of canvas data.
 * Once the cache nears a specified storage capacity, least recently used data is asynchronously removed.
 * The cache is designed to support fast concurrent access.
 *
 * @author Eric Trautman
 */
public class CanvasDataCache
        implements CanvasFeatureProvider, CanvasPeakProvider {

    private static final Map<String, CanvasDataCache> SHARED_ID_TO_CACHE_MAP = new HashMap<>();

    /**
     * @param  kilobyteCapacity  expected capacity of the shared cache.
     * @param  canvasDataLoader  expected loader implementation for the shared cache.
     *
     * @return the shared cache for the current JVM.
     *
     * @throws IllegalArgumentException
     *   if the expected parameters do not match the existing shared cache instance.
     */
    public static CanvasDataCache getSharedCache(final long kilobyteCapacity,
                                                 final CanvasDataLoader canvasDataLoader)
            throws IllegalArgumentException {

        CanvasDataCache sharedCache = SHARED_ID_TO_CACHE_MAP.get(canvasDataLoader.getDataLoaderId());

        if (sharedCache == null) {
            sharedCache = setSharedCache(kilobyteCapacity, canvasDataLoader);
        }

        if (sharedCache.kilobyteCapacity != kilobyteCapacity) {
            throw new IllegalArgumentException("The existing shared cache has capacity " +
                                               sharedCache.kilobyteCapacity + " KB but a cache with capacity " +
                                               kilobyteCapacity + " KB was requested.");
        }

        return sharedCache;
    }

    public static void clearAllSharedDataCaches() {
        LOG.info("clearAllSharedDataCaches: entry");
        SHARED_ID_TO_CACHE_MAP.clear();
    }

    private static synchronized CanvasDataCache setSharedCache(final long kilobyteCapacity,
                                                               final CanvasDataLoader canvasDataLoader) {

        CanvasDataCache sharedCache = SHARED_ID_TO_CACHE_MAP.get(canvasDataLoader.getDataLoaderId());

        if (sharedCache == null) {
            // creates "the" shared cache with statistics recording enabled
            sharedCache = new CanvasDataCache(kilobyteCapacity, canvasDataLoader, true);
            SHARED_ID_TO_CACHE_MAP.put(canvasDataLoader.getDataLoaderId(), sharedCache);
        }

        return sharedCache;
    }

    private final long kilobyteCapacity;

    private final Weigher<CanvasIdWithRenderContext, CachedCanvasData> weigher;
    private final RemovalListener<CanvasIdWithRenderContext, CachedCanvasData> asyncRemovalListener;
    private final CanvasDataLoader canvasDataLoader;

    private LoadingCache<CanvasIdWithRenderContext, CachedCanvasData> canvasIdToDataCache;

    /**
     * Creates a new cache.
     * This method is private because external access should be made through
     * {@link #getSharedCache(long, CanvasDataLoader)}.
     *
     * @param  kilobyteCapacity  capacity of the cache.
     * @param  canvasDataLoader  loader implementation for the cache.
     * @param  recordStats       indicates whether the cache should record statistics.
     *
     * @throws IllegalStateException
     *   if any errors occur.
     */
    private CanvasDataCache(final long kilobyteCapacity,
                            final CanvasDataLoader canvasDataLoader,
                            final boolean recordStats)
            throws IllegalArgumentException, IllegalStateException {

        if (kilobyteCapacity < 1) {
            this.kilobyteCapacity = 1;
        } else {
            this.kilobyteCapacity = kilobyteCapacity;
        }

        this.weigher = (key, value) -> {

            long kiloBytes = value.getKilobytes();

            // hopefully we'll never have > 2000 gigabyte file,
            // but if so it simply won't be fairly weighted
            if (kiloBytes > Integer.MAX_VALUE) {
                LOG.warn("weightOf: truncating weight for " + kiloBytes + " Kb item " + value);
                kiloBytes = Integer.MAX_VALUE;
            } else if (kiloBytes == 0) {
                // zero weights are not supported, so we need to set empty file weight to 1
                kiloBytes = 1;
            }
            return (int) kiloBytes;
        };

        // separate thread pool for removing data that expires from the cache
        final ExecutorService removalService = Executors.newFixedThreadPool(4);

        final RemovalListener<CanvasIdWithRenderContext, CachedCanvasData> removalListener =
                removal -> {
                    final CachedCanvasData cachedCanvasData = removal.getValue();
                    if (cachedCanvasData != null) {
                        cachedCanvasData.remove();
                    }
                };

        this.asyncRemovalListener = RemovalListeners.asynchronous(removalListener, removalService);
        this.canvasDataLoader = canvasDataLoader;

        this.buildCache(recordStats);

        LOG.info("<init>: exit, created {} kilobyte cache for {} data",
                 kilobyteCapacity, canvasDataLoader.getDataLoaderId());
    }

    /**
     * @return a current snapshot of this cache's cumulative statistics.
     */
    public CacheStats stats() {
        return canvasIdToDataCache.stats();
    }

    /**
     * @return the maximum number of kilobytes to be maintained in this cache.
     */
    private long getKilobyteCapacity() {
        return kilobyteCapacity;
    }

    /**
     * Looks for the specified canvas in the cache and returns the corresponding data.
     * If data for the canvas is not in the cache, the data is built (on the current thread of execution)
     * and is added to the cache before being returned.
     *
     * @param  canvasIdWithRenderContext  canvas identifier.
     *
     * @return the cached data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be cached.
     */
    private CachedCanvasData getData(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException {

        final CachedCanvasData cachedCanvasData;
        try {
            // get call should load (build) data if it is not already present
            cachedCanvasData = canvasIdToDataCache.get(canvasIdWithRenderContext);
        } catch (final Exception e) {
            throw new IllegalStateException("failed to load data for " + canvasIdWithRenderContext, e);
        }
        return cachedCanvasData;
    }

    /**
     * @return the rendered image file for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the image file cannot be cached locally.
     *
     * @throws ClassCastException
     *   if this cache is not managing {@link CachedCanvasFile} data.
     */
    public File getRenderedImage(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException, ClassCastException {
        final CachedCanvasFile cachedCanvasFile = (CachedCanvasFile) getData(canvasIdWithRenderContext);
        return cachedCanvasFile.getRenderedImage();
    }

    /**
     * @return the render parameters for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the parameters cannot be cached locally.
     *
     * @throws ClassCastException
     *   if this cache is not managing {@link CachedCanvasFile} data.
     */
    public RenderParameters getRenderParameters(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException, ClassCastException {
        final CachedCanvasFile cachedCanvasFile = (CachedCanvasFile) getData(canvasIdWithRenderContext);
        return cachedCanvasFile.getRenderParameters();
    }

    /**
     * @return the cached feature data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be cached locally.
     */
    public CachedCanvasFeatures getCanvasFeatures(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException {
        final CachedCanvasFeatures canvasFeatures;
        try {
            canvasFeatures = (CachedCanvasFeatures) getData(canvasIdWithRenderContext);
        } catch (final ClassCastException cce) {
            throw new IllegalStateException("cache does not manage CachedCanvasFeatures data", cce);
        }
        return canvasFeatures;
    }

    /**
     * @return the cached peak data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be cached locally.
     */
    public CachedCanvasPeaks getCanvasPeaks(final CanvasIdWithRenderContext canvasIdWithRenderContext)
            throws IllegalStateException, ClassCastException {
        final CachedCanvasPeaks canvasPeaks;
        try {
            canvasPeaks = (CachedCanvasPeaks) getData(canvasIdWithRenderContext);
        } catch (final ClassCastException cce) {
            throw new IllegalStateException("cache does not manage CachedCanvasPeaks data", cce);
        }
        return canvasPeaks;
    }

    @Override
    public String toString() {
        return "CanvasDataCache{" +
               "kilobyteCapacity=" + kilobyteCapacity +
               ", canvasDataLoader=" + canvasDataLoader +
               '}';
    }

    /**
     * Builds a new empty cache.
     */
    private void buildCache(final boolean recordStats) {

        // Setting concurrency level to 1 ensures global LRU eviction
        // by limiting all entries to one segment
        // (see http://stackoverflow.com/questions/10236057/guava-cache-eviction-policy ).
        // The "penalty" for this appears to be serialized put of the object
        // AFTER it has been loaded - which should not be a problem.

        final CacheBuilder<CanvasIdWithRenderContext, CachedCanvasData> cacheBuilder =
                CacheBuilder.newBuilder()
                        .concurrencyLevel(1)
                        .maximumWeight(getKilobyteCapacity())
                        .weigher(weigher)
                        .removalListener(asyncRemovalListener);

        if (recordStats) {
            cacheBuilder.recordStats();
        }

        this.canvasIdToDataCache = cacheBuilder.build(canvasDataLoader);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasDataCache.class);
}
