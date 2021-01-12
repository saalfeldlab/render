/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2020 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
/**
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
package org.janelia.render.client.solver.visualize.lazy;

import static net.imglib2.type.PrimitiveType.BYTE;
import static net.imglib2.type.PrimitiveType.DOUBLE;
import static net.imglib2.type.PrimitiveType.FLOAT;
import static net.imglib2.type.PrimitiveType.INT;
import static net.imglib2.type.PrimitiveType.LONG;
import static net.imglib2.type.PrimitiveType.SHORT;

import java.util.Set;
import java.util.function.Consumer;

import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.ArrayDataAccessFactory;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.GenericByteType;
import net.imglib2.type.numeric.integer.GenericIntType;
import net.imglib2.type.numeric.integer.GenericLongType;
import net.imglib2.type.numeric.integer.GenericShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;

/**
 * Convenience methods to create lazy evaluated cached cell images with ops or consumers.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Lazy {

	private Lazy() {}

	/**
	 * Create a memory {@link CachedCellImg} with a cell {@link Cache}.
	 *
	 * @param grid
	 * @param cache
	 * @param type
	 * @param accessFlags
	 * @return
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T extends NativeType<T>> CachedCellImg<T, ?> createImg(
			final CellGrid grid,
			final Cache<Long, Cell<?>> cache,
			final T type,
			final Set<AccessFlags> accessFlags) {

		final CachedCellImg<T, ?> img;

		if (GenericByteType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(BYTE, accessFlags));
		} else if (GenericShortType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(SHORT, accessFlags));
		} else if (GenericIntType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(INT, accessFlags));
		} else if (GenericLongType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(LONG, accessFlags));
		} else if (FloatType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, accessFlags));
		} else if (DoubleType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(DOUBLE, accessFlags));
		} else {
			img = null;
		}
		return img;
	}

	/**
	 * Create a memory {@link CachedCellImg} with a {@link CellLoader}.
	 *
	 * @param targetInterval
	 * @param blockSize
	 * @param type
	 * @param accessFlags
	 * @param loader
	 * @return
	 */
	public static <T extends NativeType<T>> CachedCellImg<T, ?> createImg(
			final Interval targetInterval,
			final int[] blockSize,
			final T type,
			final Set<AccessFlags> accessFlags,
			final CellLoader<T> loader) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(targetInterval);
		final CellGrid grid = new CellGrid(dimensions, blockSize);

		@SuppressWarnings({"unchecked", "rawtypes"})
		final Cache<Long, Cell<?>> cache =
				new SoftRefLoaderCache().withLoader(LoadedCellCacheLoader.get(grid, loader, type, accessFlags));

		return createImg(grid, cache, type, accessFlags);
	}

	/**
	 * Create a memory {@link CachedCellImg} with a cell generator {@link Consumer}.
	 *
	 * @param targetInterval
	 * @param blockSize
	 * @param type
	 * @param accessFlags
	 * @param op
	 * @return
	 */
	public static <T extends NativeType<T>> CachedCellImg<T, ?> process(
			final Interval targetInterval,
			final int[] blockSize,
			final T type,
			final Set<AccessFlags> accessFlags,
			final Consumer<RandomAccessibleInterval<T>> op) {

		return createImg(
				targetInterval,
				blockSize,
				type,
				accessFlags,
				op::accept);
	}
}
