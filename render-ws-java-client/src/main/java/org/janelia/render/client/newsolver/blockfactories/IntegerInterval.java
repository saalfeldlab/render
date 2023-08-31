package org.janelia.render.client.newsolver.blockfactories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class IntegerInterval {

	private final int min;
	private final int max;

	public IntegerInterval(final int min, final int max) {
		this.min = min;
		this.max = max;
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	public int size() {
		return max - min + 1;
	}

	public String toString() {
		return "[" + min + ", " + max + "] (#=" + size() + ")";
	}


	/**
	 * Create overlapping ND blocks in the following layout:
	 * ___________________________________________________________
	 * |             |              |             |              |
	 * |             |              |             |              |
	 * |      _______|______________|_____________|_______       |
	 * |      |             |              |             |       |
	 * |______|             |              |             |_______|
	 * |      |             |              |             |       |
	 * |      |_____________|______________|_____________|       |
	 * |      |             |              |             |       |
	 * |______|             |              |             |_______|
	 * |      |             |              |             |       |
	 * |      |_____________|______________|_____________|       |
	 * |             |              |             |              |
	 * |             |              |             |              |
	 * |_____________|______________|_____________|______________|
	 * The blocks below are called "grid"-blocks, the ones above are called "connecting"-blocks.
	 *
	 * @param min - left end of interval
	 * @param max - right end of interval
	 * @param blockSize - desired block size
	 * @return overlapping blocks that are solved individually - or null if minBlockSize is too big
	 */
	public static Map<Integer, List<IntegerInterval>> createOverlappingBlocksND(final int[] min, final int[] max, final int[] blockSize) {
		final int N = min.length;
		if (N != max.length || N != blockSize.length)
			throw new IllegalArgumentException("min, max and blockSize must have the same length");

		final List<List<IntegerInterval>> gridBlocks = new ArrayList<>(N);
		final List<List<IntegerInterval>> connectingBlocks = new ArrayList<>(N);
		for (int k = 0; k < N; k++) {
			gridBlocks.add(createGridBlocks1D(min[k], max[k], blockSize[k]));
			connectingBlocks.add(createConnectingBlocks1D(min[k], max[k], blockSize[k]));

			if (gridBlocks.get(k).isEmpty() || connectingBlocks.get(k).isEmpty())
				throw new RuntimeException(impossibleBlockErrorMessage(min[k], max[k], blockSize[k], k));
		}

		// iterate over all blocks and combine all grid blocks and all connecting blocks separately
		// since the dimension is arbitrary, we use a multiindex to iterate
		final MultiIndex gridIndex = new MultiIndex(gridBlocks.stream().mapToInt(List::size).toArray());
		final MultiIndex connectingIndex = new MultiIndex(connectingBlocks.stream().mapToInt(List::size).toArray());
		final Map<Integer, List<IntegerInterval>> blocks = new HashMap<>(gridIndex.nIndices() + connectingIndex.nIndices());

		int id = 0;
		while (gridIndex.hasNext()) {
			final List<IntegerInterval> blocks1D = new ArrayList<>(N);
			for (int k = 0; k < N; k++)
				blocks1D.add(gridBlocks.get(k).get(gridIndex.get(k)));
			blocks.put(id++, blocks1D);
			gridIndex.increment();
		}

		while (connectingIndex.hasNext()) {
			final List<IntegerInterval> blocks1D = new ArrayList<>(N);
			for (int k = 0; k < N; k++)
				blocks1D.add(connectingBlocks.get(k).get(connectingIndex.get(k)));
			blocks.put(id++, blocks1D);
			connectingIndex.increment();
		}

		return blocks;
	}


	/**
	 * 1D version of {@link #createOverlappingBlocksND(int[], int[], int[])} with convenient parameter types.
	 */
	public static Map<Integer, IntegerInterval> createOverlappingBlocks1D(final int min, final int max, final int blockSize) {
		final List<IntegerInterval> gridBlocks = createGridBlocks1D(min, max, blockSize);
		final List<IntegerInterval> connectingBlocks = createConnectingBlocks1D(min, max, blockSize);

		if (gridBlocks.isEmpty() || connectingBlocks.isEmpty())
			throw new RuntimeException(impossibleBlockErrorMessage(min, max, blockSize, 0));

		final AtomicInteger id = new AtomicInteger();
		return Stream.concat(gridBlocks.stream(), connectingBlocks.stream())
				.collect(Collectors.toMap(b -> id.getAndIncrement(), b -> b));

	}

	private static List<IntegerInterval> createGridBlocks1D(final int min, final int max, final int blockSize) {
		final int intervalSize = (max - min + 1);
		final int nBlocks = intervalSize / blockSize;

		if (nBlocks == 0)
			throw new RuntimeException(impossibleBlockErrorMessage(min, max, blockSize, 0));

		int remainder = intervalSize % blockSize;
		final int extraLayers = remainder / nBlocks + 1;

		int left = 0;
		int right = min - 1;
		final List<IntegerInterval> blocks = new ArrayList<>(nBlocks);
		for (int i = 0; i < nBlocks; ++i) {
			left = right + 1;
			right = left + blockSize - 1;

			// make first few blocks slightly larger if there is a remainder
			if (remainder > 0) {
				remainder -= extraLayers;
				right += Math.min(extraLayers, remainder);
			}
			blocks.add(new IntegerInterval(left, right));
		}

		return blocks;
	}

	private static List<IntegerInterval> createConnectingBlocks1D(final int min, final int max, final int blockSize) {
		final int shift = blockSize / 2;
		final int shrink = blockSize - shift;
		return createGridBlocks1D(min + shift, max - shrink, blockSize);
	}

	private static String impossibleBlockErrorMessage(final int min, final int max, final int blockSize, final int dim) {
		return "Could not create blocks with minimal blocksize " + blockSize
				+ " for interval [" + min + ", " + max + "] in dimension " + dim;
	}


	private static class MultiIndex {

		private final int[] index;
		private final int[] maxIndex;
		private final int N;
		private final int nIndices;

		public MultiIndex(final int[] maxIndex) {
			this.maxIndex = maxIndex;
			this.N = maxIndex.length;
			this.nIndices = Arrays.stream(maxIndex).reduce(1, (a, b) -> a * b);
			this.index = new int[N];
		}

		public boolean hasNext() {
			for (int k = 0; k < N; k++)
				if (index[k] < maxIndex[k] - 1)
					return true;
			return false;
		}

		// this is the reason why this is not a proper iterator: next is called before the loop body and returns something
		public void increment() {
			for (int k = 0; k < N; k++) {
				if (index[k] < maxIndex[k] - 1) {
					index[k]++;
					return;
				}
				index[k] = 0;
			}
		}

		public int nIndices() {
			return nIndices;
		}

		public int get(final int k) {
			return index[k];
		}
	}
}
