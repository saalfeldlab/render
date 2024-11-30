package org.janelia.alignment.util;

import java.util.Arrays;


/**
 * A space-filling Hilbert curve is a continuous curve that fills an n-dimensional space. Consecutive indices of the
 * curve are close to each other in the space. This class provides a method to generate a Hilbert curve of [0, 2^p)^n.
 * <p>
 * The implementation is based on the following paper: Skilling, J. (2004). Programming the Hilbert curve. AIP
 */
public class SpaceFillingHilbertCurve {
	public static void main(final String[] args) {
		for (int i = 0; i < 16; ++i) {
			// works!
			final long gray = indexToGray(i);
			final int[] integers = grayToCoordinates(gray, 2, 2);
			System.out.println(i + " -> " + Arrays.toString(integers));

			// doesn't work yet
//			correctCoordinates(integers, 2, 2);
//			System.out.println(i + " -> " + Arrays.toString(integers));
		}

	}

	/**
	 * Convert an index to a Gray code to create groups of indices that are close to each other in the Hilbert curve.
	 */
	private static long indexToGray(final long index) {
		return index ^ (index >> 1);
	}

	/**
	 * Convert a Gray code to a set of n-dimensional coordinates with at most nBits bits of information each.
	 */
	private static int[] grayToCoordinates(final long gray, final int nDimensions, final int nBits) {
		if (nDimensions * nBits > 64) {
			throw new IllegalArgumentException("nIntegers * nBits must be <= 64");
		}

		final int[] integers = new int[nDimensions];
		for (int i = 0; i < nDimensions; ++i) {

			// read out nDimensions interleaved integers with nBits bits each
			int integer = 0;
			for (int j = 0; j < nBits; ++j) {
				final int bitPosition = nDimensions * (nBits - j) - i - 1;
				if (bitIsSet(gray, bitPosition)) {
					integer = setBit(integer, nBits - 1 - j);
				}
			}
			integers[i] = integer;
		}

		return integers;
	}

	/**
	 * Correct the coordinates to re-orient the groups of coordinates so that they form a continuous curve.
	 */
	private static void correctCoordinates(final int[] coordinates, final int nDimensions, final int nBits) {
		for (int r = nBits - 2; r >= 0; --r) {
			for (int i = nDimensions - 1; i >= 0; --i) {
				final int coordinate = coordinates[i];

				// create a mask of the lowest bits
				int lowBits = 0;
				for (int j = r + 1; j < nBits; ++j) {
					lowBits = setBit(lowBits, j);
				}

				if (bitIsSet(coordinate, r)) {
					// swap the lowest bits of the first coordinate with the lowest bits of the current coordinate
					coordinates[i] = coordinates[0] & lowBits;
					coordinates[0] = coordinate & lowBits;
				} else {
					// invert the lowest bits of the first coordinate
					coordinates[0] = coordinates[0] ^ lowBits;
				}
			}
		}
	}

	private static boolean bitIsSet(final long binary, final int bit) {
		return (binary & (1L << bit)) != 0;
	}

	private static boolean bitIsSet(final int binary, final int bit) {
		return (binary & (1L << bit)) != 0;
	}

	private static int setBit(final int binary, final int bit) {
		return binary | (1 << bit);
	}
}
