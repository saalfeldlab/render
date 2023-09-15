package org.janelia.render.client;

import org.janelia.alignment.spec.Bounds;
import org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator;
import org.junit.Test;

import java.util.List;

import static org.janelia.render.client.newsolver.blockfactories.BlockLayoutCreator.In;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BlockLayoutCreatorTest {

	private static final int MIN = -100;
	private static final int MAX = 100;
	private static final int BLOCK_SIZE = 10;
	private static final int MIN_BLOCK_SIZE = 1;

	@Test
	public void emptyConfigurationProducesOneBlock() {
		final List<Bounds> blocks = new BlockLayoutCreator(MIN_BLOCK_SIZE).create();
		assertEquals(1, blocks.size());
	}

	@Test
	public void singleBlockHasCorrectBounds() {
		final List<Bounds> blocks = new BlockLayoutCreator(MIN_BLOCK_SIZE)
				.singleBlock(In.X, 0, MAX)
				.singleBlock(In.Y, 2*MIN, 3*MAX)
				.singleBlock(In.Z, 4*MIN, 5*MAX)
				.create();
		final Bounds bounds = blocks.get(0);
		assertEquals(0, bounds.getMinX().intValue());
		assertEquals(2*MIN, bounds.getMinY().intValue());
		assertEquals(4*MIN, bounds.getMinZ().intValue());
		assertEquals(MAX, bounds.getMaxX().intValue());
		assertEquals(3*MAX, bounds.getMaxY().intValue());
		assertEquals(5*MAX, bounds.getMaxZ().intValue());
	}

	@Test
	public void slightlyTooLargeIntervalCreatesSingleBlock() {
		final List<Bounds> blocks = new BlockLayoutCreator(MIN_BLOCK_SIZE)
				.regularGrid(In.X, 1, 2 * BLOCK_SIZE - 1, BLOCK_SIZE)
				.create();
		assertEquals(1, blocks.size());
	}

	@Test
	public void sligthlyTooSmallIntervallCreatesSingleBlock() {
		final List<Bounds> blocks = new BlockLayoutCreator(MIN_BLOCK_SIZE)
				.regularGrid(In.X, 1, BLOCK_SIZE - 1, BLOCK_SIZE)
				.create();
		assertEquals(1, blocks.size());
		assertTrue(blocks.get(0).getDeltaX() < BLOCK_SIZE);
	}

	@Test
	public void resultsAreConsistentAcrossDimensions() {
		final List<Bounds> xSlices = new BlockLayoutCreator(MIN_BLOCK_SIZE).regularGrid(In.X, MIN, MAX, BLOCK_SIZE).create();
		final List<Bounds> ySlices = new BlockLayoutCreator(MIN_BLOCK_SIZE).regularGrid(In.Y, MIN, MAX, BLOCK_SIZE).create();
		final List<Bounds> zSlices = new BlockLayoutCreator(MIN_BLOCK_SIZE).regularGrid(In.Z, MIN, MAX, BLOCK_SIZE).create();

		final int nSlices = xSlices.size();
		assertEquals(nSlices, ySlices.size());
		assertEquals(nSlices, zSlices.size());

		for (int i = 0; i < nSlices; i++) {
			// min in sliced dimension
			assertEquals(xSlices.get(i).getMinX(), ySlices.get(i).getMinY());
			assertEquals(xSlices.get(i).getMinX(), zSlices.get(i).getMinZ());
			assertEquals(ySlices.get(i).getMinY(), zSlices.get(i).getMinZ());

			// max in sliced dimension
			assertEquals(xSlices.get(i).getMaxX(), ySlices.get(i).getMaxY());
			assertEquals(xSlices.get(i).getMaxX(), zSlices.get(i).getMaxZ());
			assertEquals(ySlices.get(i).getMaxY(), zSlices.get(i).getMaxZ());

			// samples of min and max in non-sliced dimensions
			assertEquals(xSlices.get(i).getMinY(), ySlices.get(i).getMinZ());
			assertEquals(xSlices.get(i).getMinZ(), zSlices.get(i).getMinX());
			assertEquals(ySlices.get(i).getMaxZ(), zSlices.get(i).getMaxY());
		}
	}

	@Test
	public void compoundConfigurationProducesCorrectNumberOfBlocks() {
		// regular grid should have n blocks, shifted grid should have n-1 blocks per dimension
		final int n = (MAX - MIN) / BLOCK_SIZE;
		final List<Bounds> blocks = new BlockLayoutCreator(MIN_BLOCK_SIZE)
				.regularGrid(In.X, MIN, MAX, BLOCK_SIZE)
				.regularGrid(In.Y, MIN, MAX, BLOCK_SIZE)
				.everything(In.Z)
				.plus()
				.regularGrid(In.X, MIN, MAX, BLOCK_SIZE)
				.everything(In.Y)
				.shiftedGrid(In.Z, MIN, MAX, BLOCK_SIZE)
				.create();
		assertEquals(n * n + n * (n-1), blocks.size());
	}

	@Test
	public void regularGridCoversEntireDomainNonoverlapping() {
		final List<Bounds> blocks = new BlockLayoutCreator(MIN_BLOCK_SIZE)
				.regularGrid(In.X, MIN, MAX, BLOCK_SIZE)
				.regularGrid(In.Y, MIN, MAX, BLOCK_SIZE)
				.regularGrid(In.Z, MIN, MAX, BLOCK_SIZE)
				.create();

		final List<Double> volumes = blocks.stream().map(BlockLayoutCreatorTest::computeVolume).collect(java.util.stream.Collectors.toList());
		final double sideLength = MAX - MIN + 1;
		final double totalVolume = sideLength * sideLength * sideLength;
		assertEquals(totalVolume, volumes.stream().reduce(0.0, Double::sum), 1e-6);

		final Bounds combinedBounds = blocks.stream().reduce(Bounds::union).get();
		assertEquals(totalVolume, computeVolume(combinedBounds), 1e-6);
	}

	private static double computeVolume(final Bounds bounds) {
		return (bounds.getDeltaX() + 1) * (bounds.getDeltaY() + 1) * (bounds.getDeltaZ() + 1);
	}

	@Test
	public void specifyingDimensionTwiceThrowsError() {
		try {
			new BlockLayoutCreator(MIN_BLOCK_SIZE)
					.regularGrid(In.X, MIN, MAX, BLOCK_SIZE)
					.regularGrid(In.X, MIN, MAX, BLOCK_SIZE);
			fail("Expected exception not thrown");
		} catch (final RuntimeException e) {
			assertEquals("Intervals for dimension x already specified", e.getMessage());
		}
	}

	@Test
	public void tooSmallBlockThrowsError() {
		try {
			new BlockLayoutCreator(2*BLOCK_SIZE)
					.regularGrid(In.X, MIN, MAX, BLOCK_SIZE)
					.create();
			fail("Expected exception not thrown");
		} catch (final RuntimeException e) {
			assertTrue(e.getMessage().startsWith("Could not create blocks with minimal blocksize"));
		}
	}
}
