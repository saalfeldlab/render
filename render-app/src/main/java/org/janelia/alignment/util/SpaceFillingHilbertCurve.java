package org.janelia.alignment.util;

import java.util.Arrays;


/**
 * A space-filling Hilbert curve is a continuous curve that fills an n-dimensional space. Consecutive indices of the
 * curve are close to each other in the space. This class provides a method to generate a Hilbert curve of [0, 2^p)^n.
 * <p>
 * The implementation is based on the following paper: Skilling, J. (2004). Programming the Hilbert curve. AIP
 */
public class SpaceFillingHilbertCurve {

	private final int nDimensions;
	private final int nBits;

	/**
	 * Create a space-filling Hilbert curve for a grid within [0, 2^nBits)^nDimensions.
	 *
	 * @param nDimensions number of dimensions
	 * @param nBits number of bits per dimension
	 */
	public SpaceFillingHilbertCurve(final int nDimensions, final int nBits) {
		if (nDimensions * nBits > 63) {
			throw new IllegalArgumentException("nIntegers * nBits must be < 64");
		}
		this.nDimensions = nDimensions;
		this.nBits = nBits;
	}

	/**
	 * @return the maximum index that can be encoded/decoded with this Hilbert curve.
	 */
	public long maxIndex() {
		return (1L << (nDimensions * nBits)) - 1;
	}

	/**
	 * @return the maximum coordinate that can be encoded/decoded with this Hilbert curve.
	 */
	public long maxCoordinate() {
		return (1L << nBits) - 1;
	}

	/**
	 * Convert an index to a set of n-dimensional coordinates.
	 *
	 * @param index index within [0, 2^nBits)^nDimensions
	 * @return n-dimensional coordinates
	 */
	public long[] decode(final long index) {
		if (index < 0 || index > maxIndex()) {
			throw new IllegalArgumentException("Index out of bounds [0, " + maxIndex() + "]: " + index);
		}

		final long gray = indexToGray(index);
		final long[] coordinates = grayToCoordinates(gray);
		correctCoordinates(coordinates);
		return coordinates;
	}

	/**
	 * Convert a set of n-dimensional coordinates to an index.
	 *
	 * @param coordinates n-dimensional coordinates
	 * @return index within [0, 2^nBits)^nDimensions
	 */
	public long encode(final long[] coordinates) {
		if (coordinates.length < nDimensions) {
			throw new IllegalArgumentException("Number of coordinates must be at least number of dimensions: " + coordinates.length);
		}
		for (int i = 0; i < nDimensions; ++i) {
			if (coordinates[i] < 0 || coordinates[i] > maxCoordinate()) {
				throw new IllegalArgumentException("Coordinate in dimension " + i + " out of bounds [0, " + maxCoordinate() + "]: " + coordinates[i]);
			}
		}

		final long[] clonedCoordinates = coordinates.clone();
		unCorrectCoordinates(clonedCoordinates);
		final long gray = coordinatesToGray(clonedCoordinates);
		return grayToIndex(gray);
	}

	/**
	 * Convert a Gray code to a set of n-dimensional coordinates with at most nBits bits of information each.
	 */
	private long[] grayToCoordinates(final long gray) {

		final long[] coordinates = new long[nDimensions];
		for (int i = 0; i < nDimensions; ++i) {

			// read out nDimensions interleaved coordinates with nBits bits each
			long coordinate = 0;
			for (int j = 0; j < nBits; ++j) {
				final int bitPosition = nDimensions * (nBits - j) - i - 1;
				if (bitIsSet(gray, bitPosition)) {
					coordinate = setBit(coordinate, nBits - 1 - j);
				}
			}
			coordinates[i] = coordinate;
		}

		return coordinates;
	}

	/**
	 * Convert a set of n-dimensional coordinates to a Gray code.
	 */
	private long coordinatesToGray(final long[] coordinates) {
		long gray = 0;
		for (int i = 0; i < nDimensions; ++i) {
			for (int j = 0; j < nBits; ++j) {
				final int bitPosition = nDimensions * (nBits - j) - i - 1;
				if (bitIsSet(coordinates[i], nBits - 1 - j)) {
					gray = setBit(gray, bitPosition);
				}
			}
		}
		return gray;
	}

	/**
	 * Correct the coordinates to re-orient the groups of coordinates so that they form a continuous curve.
	 */
	private void correctCoordinates(final long[] coordinates) {
		for (int r = 1; r < nBits; ++r) {
			for (int i = nDimensions - 1; i >= 0; --i) {
				correctBitsOfCoordinates(coordinates, r, i);
			}
		}
	}

	private void unCorrectCoordinates(final long[] coordinates) {
		for (int r = nBits - 1; r > 0; --r) {
			for (int i = 0; i < nDimensions; ++i) {
				correctBitsOfCoordinates(coordinates, r, i);
			}
		}
	}

	private static void correctBitsOfCoordinates(final long[] coordinates, final int r, final int i) {
		final int lowBits = (1 << r) - 1;
		if (bitIsSet(coordinates[i], r)) {
			// invert the lowest bits of the first coordinate
			coordinates[0] ^= lowBits;
		} else {
			// swap the lowest bits of the first coordinate with the lowest bits of the current coordinate
			final long swap = (coordinates[0] ^ coordinates[i]) & lowBits;
			coordinates[0] ^= swap;
			coordinates[i] ^= swap;
		}
	}

	/**
	 * Convert an index to a Gray code to create groups of indices that are close to each other in the Hilbert curve.
	 */
	private static long indexToGray(final long index) {
		return index ^ (index >> 1);
	}

	/**
	 * Convert a Gray code back to an index.
	 */
	private static long grayToIndex(final long gray) {
		long index = gray;
		long mask = gray;
		while (mask != 0) {
			mask >>= 1;
			index ^= mask;
		}
		return index;
	}

	private static boolean bitIsSet(final long binary, final int bit) {
		return (binary & (1L << bit)) != 0;
	}

	private static long setBit(final long binary, final int bit) {
		return binary | (1L << bit);
	}
}
