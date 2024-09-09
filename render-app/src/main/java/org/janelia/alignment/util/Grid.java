/*
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.n5.DataBlock;

import net.imglib2.Interval;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Grid {

	private Grid() {}

	/**
	 * Crops the dimensions of a {@link DataBlock} at a given offset to fit
	 * into an {@link Interval} of given dimensions.  Fills long and int
	 * version of cropped block size.  Also calculates the grid raster position
	 * assuming that the offset divisible by block size without remainder.
	 */
	static void cropBlockDimensions(
			final long[] dimensions,
			final long[] offset,
			final int[] outBlockSize,
			final int[] blockSize,
			final long[] croppedBlockSize,
			final long[] gridPosition) {

		for (int d = 0; d < dimensions.length; ++d) {
			croppedBlockSize[d] = Math.min(blockSize[d], dimensions[d] - offset[d]);
			gridPosition[d] = offset[d] / outBlockSize[d];
		}
	}

	/**
	 * Create a {@link List} of grid blocks that, for each grid cell, contains
	 * the world coordinate offset, the size of the grid block, and the
	 * grid-coordinate offset.  The spacing for input grid and output grid
	 * are independent, i.e. world coordinate offsets and cropped block-sizes
	 * depend on the input grid, and the grid coordinates of the block are
	 * specified on an independent output grid.  It is assumed that
	 * gridBlockSize is an integer multiple of outBlockSize.
	 */
	public static List<Block> create(
			final long[] dimensions,
			final int[] gridBlockSize,
			final int[] outBlockSize) {

		final int n = dimensions.length;
		final List<Block> gridBlocks = new ArrayList<>();

		final long[] offset = new long[n];
		final long[] gridPosition = new long[n];
		final long[] longCroppedGridBlockSize = new long[n];
		for (int d = 0; d < n;) {
			cropBlockDimensions(dimensions, offset, outBlockSize, gridBlockSize, longCroppedGridBlockSize, gridPosition);
			gridBlocks.add(new Block(longCroppedGridBlockSize, offset, gridPosition));

			for (d = 0; d < n; ++d) {
				offset[d] += gridBlockSize[d];
				if (offset[d] < dimensions[d])
					break;
				else
					offset[d] = 0;
			}
		}
		return gridBlocks;
	}

	/**
	 * Create a {@link List} of grid blocks that, for each grid cell, contains
	 * the world coordinate offset, the size of the grid block, and the
	 * grid-coordinate offset.
	 */
	public static List<Block> create(
			final long[] dimensions,
			final int[] blockSize) {

		return create(dimensions, blockSize, blockSize);
	}

	public static class Block implements Serializable, Interval {
		public final long[] dimensions;
		public final long[] offset;
		public final long[] gridPosition;

		public Block(final long[] dimensions, final long[] offset, final long[] gridPosition) {
			this.dimensions = dimensions.clone();
			this.offset = offset.clone();
			this.gridPosition = gridPosition.clone();

			if (dimensions.length != offset.length || dimensions.length != gridPosition.length)
				throw new IllegalArgumentException("Dimensions of block, offset, and grid position must match.");
		}

		public Block(final Interval interval, final long[] gridPosition) {
			this(interval.dimensionsAsLongArray(), interval.minAsLongArray(), gridPosition);
		}

		@Override
		public long min(final int i) {
			return offset[i];
		}

		@Override
		public long max(final int i) {
			return offset[i] + dimensions[i] - 1;
		}

		@Override
		public int numDimensions() {
			return dimensions.length;
		}
	}
}
