/*
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

package org.janelia.render.client.solver.visualize.lazy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Sampler;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.gauss3.SeparableSymmetricConvolution;
import net.imglib2.converter.Converters;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * Simple Gaussian filter Op
 *
 * @author Stephan Saalfeld
 * @author Stephan Preibisch
 * @param <T> type of input and output
 */
public class RenderRA<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final T type;
	final long[] globalMin;
	final ImageProcessorCache ipCache;
	String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
	String owner = "flyem";
	String project = "Z0419_25_Alpha3";
	String stack = "v1_acquire_sp_translation_nodyn";

	final double scale;

	public RenderRA(
			final ImageProcessorCache ipCache,
			final long[] min,
			final T type,
			final double scale)
	{
		this.ipCache = ipCache;
		this.globalMin = min;
		this.type = type;
		this.scale = scale;
	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		// output is in scaled space
		// blocksize should be power-of-2

		try
		{
			final int x = (int)Math.round( output.min( 0 ) / scale );
			final int y = (int)Math.round( output.min( 1 ) / scale );

			final int w = (int)Math.round( output.dimension( 0 ) / scale );
			final int h = (int)Math.round( output.dimension( 1 ) / scale );

			// we assume that the blocksize in z == 1
			final int z = (int)Math.round( output.min( 2 ) / scale );

			ImageProcessor ip = RenderTools.renderImage(ipCache, baseUrl, owner, project, stack, x, y, z, w, h, z, false ).ip;

			final long[] min = new long[ output.numDimensions() ];
			output.min( min );

			RandomAccessibleInterval img = Views.translate( (RandomAccessibleInterval)ImageJFunctions.wrapReal( new ImagePlus( "", ip ) ), min );

			final Cursor< T > out = Views.flatIterable( output ).cursor();
			final Cursor< RealType > in = Views.flatIterable( img ).cursor();

			while( out.hasNext() )
				out.next().setReal( in.next().getRealDouble() );
		}
		catch (final IncompatibleTypeException e)
		{
			throw new RuntimeException(e);
		}
	}
}
