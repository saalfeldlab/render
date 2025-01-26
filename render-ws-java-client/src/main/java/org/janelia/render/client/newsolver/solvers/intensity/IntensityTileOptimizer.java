package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;


/**
 * Concurrent optimizer for collections of {@link IntensityTile}s. This is basically a slightly modified implementation
 * of {@link mpicbg.models.TileUtil#optimizeConcurrently(ErrorStatistic, double, int, int, double, TileConfiguration, Set, Set, int, boolean)},
 * which is necessary because {@link IntensityTile} doesn't derive from {@link Tile}.
 * Also, some methods of {@link mpicbg.models.TileConfiguration} are re-implemented here for the same reason.
 * <p>
 * Since an {@link IntensityTile} hides the sub-tiles it contains, this reduces the parallelization overhead of the
 * optimizer, which would otherwise have to synchronize access to the (large number of) sub-tiles.
 */
class IntensityTileOptimizer {

	private static final Logger LOG = LoggerFactory.getLogger(IntensityTileOptimizer.class);

	private final double maxAllowedError;
	private final int maxIterations;
	private final int maxPlateauWidth;
	private final double damp;
	private final int nThreads;

	public IntensityTileOptimizer(
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauWidth,
			final double damp,
			final int nThreads
	) {
		this.maxAllowedError = maxAllowedError;
		this.maxIterations = maxIterations;
		this.maxPlateauWidth = maxPlateauWidth;
		this.damp = damp;
		this.nThreads = nThreads;
	}

	public void optimize(
			final List<IntensityTile> tiles,
			final List<IntensityTile> fixedTiles
		) {

		final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(nThreads);
		try {
			final long t0 = System.currentTimeMillis();
			final ErrorStatistic observer = new ErrorStatistic(maxIterations + 1);

			final List<IntensityTile> freeTiles = new ArrayList<>(tiles);
			freeTiles.removeAll(fixedTiles);
			Collections.shuffle(freeTiles);

			final long t1 = System.currentTimeMillis();
			LOG.debug("Shuffling took {} ms", t1 - t0);

			/* initialize the configuration with the current model of each tile */
			applyAll(tiles, executor);

			final long t2 = System.currentTimeMillis();
			LOG.debug("First apply took {} ms", t2 - t1);

			int i = 0;
			boolean proceed = i < maxIterations;
			final Set<IntensityTile> executingTiles = ConcurrentHashMap.newKeySet();

			while (proceed) {
				Collections.shuffle(freeTiles);
				final Deque<IntensityTile> pending = new ConcurrentLinkedDeque<>(freeTiles);
				final List<Future<Void>> tasks = new ArrayList<>(nThreads);

				for (int j = 0; j < nThreads; j++) {
					final boolean cleanUp = (j == 0);
					tasks.add(executor.submit(() -> fitAndApplyWorker(pending, executingTiles, damp, cleanUp)));
				}

				for (final Future<Void> task : tasks) {
					try {
						task.get();
					} catch (final InterruptedException | ExecutionException e) {
						throw new RuntimeException(e);
					}
				}

				final double error = computeErrors(tiles, executor);
				observer.add(error);

				LOG.debug("{}: {} {}", i, error, observer.max);

				if (i > maxPlateauWidth) {
					proceed = error > maxAllowedError;

					int d = maxPlateauWidth;
					while (!proceed && d >= 1) {
						try {
							proceed = Math.abs(observer.getWideSlope(d)) > 0.0001;
						} catch (final Exception e) {
							LOG.warn("Error while computing slope: {}", e.getMessage());
						}
						d /= 2;
					}
				}

				proceed &= ++i < maxIterations;
			}

			final long t3 = System.currentTimeMillis();
			LOG.info("Concurrent tile optimization loop took {} ms, total took {} ms", t3 - t2, t3 - t0);

		} finally {
			executor.shutdownNow();
		}
	}

	private static void applyAll(final List<IntensityTile> tiles, final ThreadPoolExecutor executor) {
		final int nTiles = tiles.size();
		final int nThreads = executor.getMaximumPoolSize();
		final int tilesPerThread = nTiles / nThreads + (nTiles % nThreads == 0 ? 0 : 1);
		final List<Future<Void>> applyTasks = new ArrayList<>(nThreads);

		for (int j = 0; j < nThreads; j++) {
			final int start = j * tilesPerThread;
			final int end = Math.min((j + 1) * tilesPerThread, nTiles);
			applyTasks.add(executor.submit(() -> applyToRange(tiles, start, end)));
		}

		for (final Future<Void> task : applyTasks) {
			try {
				task.get();
			} catch (final InterruptedException | ExecutionException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static Void applyToRange(final List<IntensityTile> tiles, final int start, final int end) {
		for (int i = start; i < end; i++) {
			final IntensityTile t = tiles.get(i);
			t.apply();
		}
		return null;
	}

	private static double computeErrors(final List<IntensityTile> tiles, final ThreadPoolExecutor executor) {
		final int nTiles = tiles.size();
		final int nThreads = executor.getMaximumPoolSize();
		final int tilesPerThread = nTiles / nThreads + (nTiles % nThreads == 0 ? 0 : 1);
		final List<Future<DoubleSummaryStatistics>> applyTasks = new ArrayList<>(nThreads);

		for (int j = 0; j < nThreads; j++) {
			final int start = j * tilesPerThread;
			final int end = Math.min((j + 1) * tilesPerThread, nTiles);
			applyTasks.add(executor.submit(() -> computeErrorsOfRange(tiles, start, end)));
		}

		final DoubleSummaryStatistics totalStats = new DoubleSummaryStatistics();
		for (final Future<DoubleSummaryStatistics> task : applyTasks) {
			try {
				final DoubleSummaryStatistics taskStats = task.get();
				totalStats.combine(taskStats);
			} catch (final InterruptedException | ExecutionException e) {
				throw new RuntimeException(e);
			}
		}

		return totalStats.getAverage();
	}

	private static DoubleSummaryStatistics computeErrorsOfRange(final List<IntensityTile> tiles, final int start, final int end) {
		final DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
		for (int i = start; i < end; i++) {
			final IntensityTile t = tiles.get(i);
			t.updateDistance();
			stats.accept(t.getDistance());
		}
		return stats;
	}

	private static Void fitAndApplyWorker(
			final Deque<IntensityTile> pendingTiles,
			final Set<IntensityTile> executingTiles,
			final double damp,
			final boolean cleanUp
	) throws NotEnoughDataPointsException, IllDefinedDataPointsException {

		final int n = pendingTiles.size();
		for (int i = 0; (i < n) || cleanUp; i++){
			// the polled tile can only be null if the deque is empty, i.e., there is no more work
			final IntensityTile tile = pendingTiles.pollFirst();
			if (tile == null)
				return null;

			executingTiles.add(tile);
			final boolean canBeProcessed = Collections.disjoint(tile.getConnectedTiles(), executingTiles);

			if (canBeProcessed) {
				tile.fitAndApply(damp);
				executingTiles.remove(tile);
			} else {
				executingTiles.remove(tile);
				pendingTiles.addLast(tile);
			}
		}
		return null;
	}
}
