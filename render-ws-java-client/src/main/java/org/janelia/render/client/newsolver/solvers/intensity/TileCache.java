package org.janelia.render.client.newsolver.solvers.intensity;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.janelia.alignment.spec.TileSpec;

/**
 * A small weight-bounded, least-recently-used cache of per-tile values, keyed by tile id and backed
 * by Guava. Adapted (and simplified) from {@link org.janelia.alignment.util.ImageProcessorCache}:
 * entries are weighed in kilobytes and, once their total exceeds the configured budget,
 * least-recently-used entries are evicted (and reloaded on their next request).
 * <p>
 * Values are loaded on a miss by the supplied loader, which is given the requesting {@link TileSpec}
 * so it has everything needed to (re)build the value; the cache key is the tile id. Concurrent
 * requests for the same tile load the value only once (other threads block until it is available).
 * Cached values are shared across threads, so callers must treat them as immutable.
 *
 * @param <T> the cached value type
 */
class TileCache<T> {

	private final Cache<String, T> cache;
	private final Function<TileSpec, T> loader;

	/**
	 * @param maximumKilobytes total weight budget in kilobytes; least-recently-used entries are
	 *                         evicted once the cached total would exceed it
	 * @param kilobytesOf      weight of a single value in kilobytes (used for eviction)
	 * @param loader           loads the value for a tile on a cache miss
	 */
	TileCache(final long maximumKilobytes,
			  final ToIntFunction<T> kilobytesOf,
			  final Function<TileSpec, T> loader) {
		this.loader = loader;
		final Weigher<String, T> weigher = (key, value) -> Math.max(1, kilobytesOf.applyAsInt(value));
		this.cache = CacheBuilder.newBuilder()
				.maximumWeight(maximumKilobytes)
				.weigher(weigher)
				.build();
	}

	/**
	 * Returns the value for {@code patch} (keyed by its tile id), loading and caching it on a miss.
	 *
	 * @throws IllegalArgumentException if the loader fails
	 */
	T get(final TileSpec patch) {
		try {
			return cache.get(patch.getTileId(), () -> loader.apply(patch));
		} catch (final ExecutionException | UncheckedExecutionException e) {
			throw new IllegalArgumentException("failed to load cached value for tile " + patch.getTileId(),
											   e.getCause());
		}
	}
}
