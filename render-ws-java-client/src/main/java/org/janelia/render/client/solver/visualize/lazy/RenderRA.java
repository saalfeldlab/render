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

import java.util.function.Consumer;

import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.solver.visualize.RenderTools;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
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
	final String baseUrl;// = "http://tem-services.int.janelia.org:8080/render-ws/v1";
	final String owner;// = "flyem";
	final String project;// = "Z0419_25_Alpha3";
	final String stack;// = "v1_acquire_sp_translation_nodyn";

	final long minZ, maxZ;
	final double scale;

	public RenderRA(
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final long minZ, // full-res stack
			final long maxZ, // full-res stack
			final ImageProcessorCache ipCache,
			final long[] min,
			final T type,
			final double scale )
	{
		this.baseUrl = baseUrl;
		this.owner = owner;
		this.project = project;
		this.stack = stack;
		this.ipCache = ipCache;
		this.globalMin = min;
		this.type = type;
		this.scale = scale;
		this.minZ = minZ;
		this.maxZ = maxZ;
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
			final long[] min= new long[ output.numDimensions() ];
			for ( int d = 0; d < min.length; ++d )
				min[ d ] = globalMin[ d ] + output.min( d );

			final int x = (int)Math.round( min[ 0 ] / scale );
			final int y = (int)Math.round( min[ 1 ] / scale );

			final int w = (int)Math.round( output.dimension( 0 ) / scale );
			final int h = (int)Math.round( output.dimension( 1 ) / scale );

			// we assume that the blocksize in z == 1
			final int z = (int) (Math.round( output.min( 2 ) / scale ) + minZ);

			if ( z < minZ || z > maxZ )
			{
				fillZero( output );
			}
			else
			{
				update(output, x, y, z, w, h );
			}
		}
		catch (final IncompatibleTypeException e)
		{
			throw new RuntimeException(e);
		}
	}

	protected void update( final RandomAccessibleInterval<T> output, final int x, final int y, final int z, final int w, final int h )
	{
		ImageProcessorWithMasks ipm = RenderTools.renderImage( ipCache, baseUrl, owner, project, stack, x, y, z, w, h, scale, false );

		if ( ipm == null ) // if the requested block contains no images, null will be returned
		{
			fillZero( output );
			return;
		}

		final Cursor< T > out = Views.flatIterable( output ).cursor();
		int i = 0;

		while( out.hasNext() )
			out.next().setReal( ipm.ip.getf( i++ ) );
	}

	protected void fillZero( final RandomAccessibleInterval<T> output )
	{
		for ( final T t : Views.iterable( output ) )
			t.setZero();
	}
}
