package org.janelia.render.client.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalListeners;
import com.google.common.cache.Weigher;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.match.CanvasId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class manages a singleton cache of canvas data.
 * Once the cache nears a specified storage capacity, least recently used data is asynchronously removed.
 * The cache is designed to support fast concurrent access.
 *
 * @author Eric Trautman
 */
public class CanvasDataCache {

    private static CanvasDataCache sharedCache;

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

        if (sharedCache == null) {
            setSharedCache(kilobyteCapacity, canvasDataLoader);
        }

        if (sharedCache.kilobyteCapacity != kilobyteCapacity) {
            throw new IllegalArgumentException("The exisitng shared cache has capacity " +
                                               sharedCache.kilobyteCapacity + " KB but a cache with capicity " +
                                               kilobyteCapacity + " KB was requested.");
        }

        if (! sharedCache.canvasDataLoader.getDataClass().equals(canvasDataLoader.getDataClass())) {
            throw new IllegalArgumentException("The exisitng shared cache manages " +
                                               sharedCache.canvasDataLoader.getDataClass() +
                                               " elements but a cache that manages " +
                                               canvasDataLoader.getDataClass() + " elements was requested.");
        }

        return sharedCache;
    }

    private static synchronized void setSharedCache(final long kilobyteCapacity,
                                                    final CanvasDataLoader canvasDataLoader) {
        if (sharedCache == null) {
            // creates "the" shared cache with statistics recording enabled
            sharedCache = new CanvasDataCache(kilobyteCapacity, canvasDataLoader, true);
        }
    }

    private final long kilobyteCapacity;

    private final Weigher<CanvasId, CachedCanvasData> weigher;
    private final RemovalListener<CanvasId, CachedCanvasData> asyncRemovalListener;
    private final CanvasDataLoader canvasDataLoader;

    private LoadingCache<CanvasId, CachedCanvasData> canvasIdToDataCache;

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

        final RemovalListener<CanvasId, CachedCanvasData> removalListener =
                removal -> {
                    final CachedCanvasData cachedCanvasData = removal.getValue();
                    if (cachedCanvasData != null) {
                        cachedCanvasData.remove();
                    }
                };

        this.asyncRemovalListener = RemovalListeners.asynchronous(removalListener, removalService);
        this.canvasDataLoader = canvasDataLoader;

        this.buildCache(recordStats);

        LOG.info("<init>: exit");
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
     * @param  canvasId  canvas identifier.
     *
     * @return the cached data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be cached.
     */
    private CachedCanvasData getData(final CanvasId canvasId)
            throws IllegalStateException {

        final CachedCanvasData cachedCanvasData;
        try {
            // get call should load (build) data if it is not already present
            cachedCanvasData = canvasIdToDataCache.get(canvasId);
        } catch (final Exception e) {
            throw new IllegalStateException("failed to load data for " + canvasId, e);
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
    public File getRenderedImage(final CanvasId canvasId)
            throws IllegalStateException, ClassCastException {
        final CachedCanvasFile cachedCanvasFile = (CachedCanvasFile) getData(canvasId);
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
    public RenderParameters getRenderParameters(final CanvasId canvasId)
            throws IllegalStateException, ClassCastException {
        final CachedCanvasFile cachedCanvasFile = (CachedCanvasFile) getData(canvasId);
        return cachedCanvasFile.getRenderParameters();
    }

    /**
     * @return the cached feature data for the specified canvas.
     *
     * @throws IllegalStateException
     *   if the data cannot be cached locally.
     *
     * @throws ClassCastException
     *   if this cache is not managing {@link CachedCanvasFeatures} data.
     */
    public CachedCanvasFeatures getCanvasFeatures(final CanvasId canvasId)
            throws IllegalStateException, ClassCastException {
        return (CachedCanvasFeatures) getData(canvasId);
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

        final CacheBuilder<CanvasId, CachedCanvasData> cacheBuilder =
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
