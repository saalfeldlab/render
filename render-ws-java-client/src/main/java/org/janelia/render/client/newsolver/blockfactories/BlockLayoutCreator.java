package org.janelia.render.client.newsolver.blockfactories;

import org.janelia.alignment.spec.Bounds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BlockLayoutCreator {

	public enum In {

		X(0, "dimension x"),
		Y(1, "dimension y"),
		Z(2, "dimension z");

		private final int index;
		private final String name;

		In(final int index, final String name) {
			this.index = index;
			this.name = name;
		}

		public int index() {
			return index;
		}

		@Override
		public String toString() {
			return name;
		}
	}


	private final int[] minBlockSize;
	private final List<List<IntegerInterval>> intervalCollections;
	private final List<Bounds> blocks;

	public BlockLayoutCreator(final int minBlockSize) {
		this(new int[]{minBlockSize, minBlockSize, minBlockSize});
	}

	public BlockLayoutCreator(final int[] minBlockSize) {
		this(minBlockSize, new ArrayList<>());
	}

	private BlockLayoutCreator(final int[] minBlockSize, final List<Bounds> existingBlocks) {
		if (minBlockSize.length != 3)
			throw new RuntimeException("minBlockSize has to be of length 3");

		this.minBlockSize = minBlockSize;
		this.blocks = existingBlocks;
		this.intervalCollections = Arrays.asList(null, null, null);
	}

	/**
	 * Specify everything in the given dimension.
	 *
	 * @param dimension - dimension to specify
	 * @return BlockLayoutCreator for chaining
	 */
	public BlockLayoutCreator everything(final In dimension) {
		return singleBlock(dimension, Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	/**
	 * Specify a single block with given size in the given dimension.
	 *
	 * @param dimension - dimension to specify
	 * @param min - left end of interval
	 * @param max - right end of interval
	 * @return BlockLayoutCreator for chaining
	 */
	public BlockLayoutCreator singleBlock(final In dimension, final int min, final int max) {
		final List<IntegerInterval> targetIntervals = getUnspecifiedIntervals(dimension);
		targetIntervals.add(new IntegerInterval(min, max));
		return this;
	}

	/**
	 * Specify a regular grid of blocks with given size in the given dimension. The size of the resulting blocks is
	 * guaranteed to be {@code >= blockSize} except if the interval is smaller than {@code blockSize}.
	 *
	 * @param dimension - dimension to specify
	 * @param min - left end of interval
	 * @param max - right end of interval
	 * @param blockSize - desired block size
	 * @return BlockLayoutCreator for chaining
	 */
	public BlockLayoutCreator regularGrid(final In dimension, final int min, final int max, final int blockSize) {
		final List<IntegerInterval> targetIntervals = getUnspecifiedIntervals(dimension);
		targetIntervals.addAll(createGrid(min, max, blockSize));
		return this;
	}

	/**
	 * Specify a regular grid of blocks with given size in the given dimension. The grid is shifted by half a block
	 * size to the right and consists of one block less compared to {@link #regularGrid(In, int, int, int)}.
	 * The size of the resulting blocks is guaranteed to be {@code >= blockSize} but the grid may be empty if the
	 * size of the interval is smaller than {@code 2*blockSize}.
	 *
	 * @param dimension - dimension to specify
	 * @param min - left end of interval
	 * @param max - right end of interval
	 * @param blockSize - desired block size
	 * @return BlockLayoutCreator for chaining
	 */
	public BlockLayoutCreator shiftedGrid(final In dimension, final int min, final int max, final int blockSize) {
		final List<IntegerInterval> targetIntervals = getUnspecifiedIntervals(dimension);
		if (max - min + 1 >= 2*blockSize) {
			// only do this if the grid is large enough to comfortably accommodate an "in-between" block
			final int shift = blockSize / 2;
			final int shrink = blockSize - shift;
			targetIntervals.addAll(createGrid(min + shift, max - shrink, blockSize));
		}
		return this;
	}

	/**
	 * Start a new creation cycle but keep already created blocks.
	 *
	 * @return BlockLayoutCreator for chaining
	 */
	public BlockLayoutCreator plus() {
		return new BlockLayoutCreator(minBlockSize, create());
	}

	/**
	 * Create blocks as specified. For any unspecified dimension, {@link #everything(In)} is assumed.
	 *
	 * @return list of {@link Bounds} with the specified dimensions
	 */
	public List<Bounds> create() {
		ensureAllDimensionsSpecified();
		final List<IntegerInterval> xIntervals = intervalCollections.get(In.X.index);
		final List<IntegerInterval> yIntervals = intervalCollections.get(In.Y.index);
		final List<IntegerInterval> zIntervals = intervalCollections.get(In.Z.index);

		for (final IntegerInterval xInterval : xIntervals) {
			if (xInterval.size() < minBlockSize[In.X.index])
				throw new RuntimeException(impossibleBlockErrorMessage(minBlockSize[In.X.index], xInterval, In.X));

			for (final IntegerInterval yInterval : yIntervals) {
				if (yInterval.size() < minBlockSize[In.Y.index])
					throw new RuntimeException(impossibleBlockErrorMessage(minBlockSize[In.Y.index], yInterval, In.Y));

				for (final IntegerInterval zInterval : zIntervals) {
					if (zInterval.size() < minBlockSize[In.Z.index])
						throw new RuntimeException(impossibleBlockErrorMessage(minBlockSize[In.Z.index], zInterval, In.Z));

					blocks.add(new Bounds(
							xInterval.min(), yInterval.min(), zInterval.min(),
							xInterval.max(), yInterval.max(), zInterval.max()));
				}
			}
		}

		return blocks;
	}

	private void ensureAllDimensionsSpecified() {
		for (final In dimension : In.values())
			if (intervalCollections.get(dimension.index) == null)
				everything(dimension);
	}

	private List<IntegerInterval> createGrid(final int min, final int max, final int intervalSize) {
		final int totalSize = (max - min + 1);
		final int nIntervals = totalSize / intervalSize;

		if (nIntervals == 0)
			// create a single interval of maximal size; the size is checked afterward
			return List.of(new IntegerInterval(min, max));

		int remainder = totalSize % intervalSize;
		final int extraSize = remainder / nIntervals + 1;
		final List<IntegerInterval> intervals = new ArrayList<>(nIntervals);
		int left, right = min - 1;

		for (int i = 0; i < nIntervals; ++i) {
			left = right + 1;
			right = left + intervalSize - 1;

			// make first few intervals slightly larger if there is a remainder
			if (remainder > 0) {
				right += Math.min(extraSize, remainder);
				remainder -= extraSize;
			}
			intervals.add(new IntegerInterval(left, right));
		}

		return intervals;
	}

	private List<IntegerInterval> getUnspecifiedIntervals(final In dimension) {
		final List<IntegerInterval> targetInterval = new ArrayList<>();
		if (intervalCollections.set(dimension.index, targetInterval) != null)
			throw new RuntimeException("Intervals for " + dimension + " already specified");
		return targetInterval;
	}

	private static String impossibleBlockErrorMessage(final int minBlockSize, final IntegerInterval interval, final In dimension) {
		return "Could not create blocks with minimal blocksize " + minBlockSize
				+ " for interval " + interval + " in dimension " + dimension;
	}


	private static class IntegerInterval {
		private final int min;
		private final int max;

		public IntegerInterval(final int min, final int max) {
			this.min = min;
			this.max = max;
		}

		public double min() {
			return min;
		}

		public double max() {
			return max;
		}

		public long size() {
			return (long)max - (long)min + 1;
		}

		public String toString() {
			return "[" + min + ", " + max + "] (#=" + size() + ")";
		}
	}
}
