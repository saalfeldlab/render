package org.janelia.render.client.solver.visualize;

import java.util.ArrayList;
import java.util.HashMap;

import mpicbg.models.AffineModel2D;

import org.janelia.alignment.spec.TileSpec;

import net.imglib2.AbstractLocalizable;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;

public class VisualizingRandomAccess extends AbstractLocalizable implements RandomAccess< FloatType >
{
	final HashMap<String, AffineModel2D> idToInvertedRenderModels;
	final HashMap<Integer, ArrayList<Pair<String, TileSpec>>> zToTileSpec; // at full resolution
	final HashMap<String, Float> idToValue;
	final double[] scale, tmp;
	final FloatType type;

	public VisualizingRandomAccess(
			final HashMap<String, AffineModel2D> idToInvertedRenderModels,
			final HashMap<Integer, ArrayList<Pair<String, TileSpec>>> zToTileSpec,
			final HashMap<String, Float> idToValue,
			final double[] scale )
	{
		// dimensionality
		super( 3 );

		this.idToValue = idToValue;
		this.idToInvertedRenderModels = idToInvertedRenderModels;
		this.zToTileSpec = zToTileSpec;
		this.scale = scale;
		this.type = new FloatType();
		this.tmp = new double[ 2 ];
	}

	@Override
	public FloatType get()
	{
		// the position is in the scaled space and needs to be mapped to full res
		final int z = (int)Math.round( this.position[ 2 ] / scale[ 2 ] );

		final ArrayList<Pair<String, TileSpec>> entries = zToTileSpec.get(z);

		if ( entries == null )
		{
			type.set( -1.0f );
			return type;
		}

		float value = 0;

		for (final Pair<String, TileSpec> pair : entries)
		{
			final String tileId = pair.getA();
			final int w = pair.getB().getWidth();
			final int h = pair.getB().getHeight();
			final AffineModel2D model = idToInvertedRenderModels.get( tileId );
			
			tmp[ 0 ] = this.getLongPosition( 0 );
			tmp[ 1 ] = this.getLongPosition( 1 );
			
			model.applyInPlace( tmp );

			if ( tmp[ 0 ] >= 0 && tmp[ 1 ] >= 0 && tmp[ 0 ] <= w - 1 && tmp[ 1 ] <= h - 1 )
			{
				value += idToValue.get( tileId ); //1
			}
		}

		type.set( value );
		return type;
	}

	@Override
	public void fwd( final int d ) { ++this.position[ d ]; }

	@Override
	public void bck( final int d ) { --this.position[ d ]; }

	@Override
	public void move(final int distance, final int d) { this.position[ d ] += distance; }

	@Override
	public void move(final long distance, final int d) { this.position[ d ] += distance; }

	@Override
	public void move(final Localizable distance)
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += distance.getIntPosition( d );
	}

	@Override
	public void move( final int[] distance)
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance)
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += distance[ d ];
	}

	@Override
	public void setPosition(final Localizable position)
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = position.getIntPosition( d );
	}

	@Override
	public void setPosition(final int[] position)
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = position[ d ];
	}

	@Override
	public void setPosition(final long[] position)
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = position[ d ];
	}

	@Override
	public void setPosition(final int position, final int d)
	{
		this.position[ d ] = position;
	}

	@Override
	public void setPosition(final long position, final int d)
	{
		this.position[ d ] = position;
	}

	@Override
	public RandomAccess<FloatType> copy() {
		return copyRandomAccess();
	}

	@Override
	public RandomAccess<FloatType> copyRandomAccess()
	{
		final VisualizingRandomAccess r = new VisualizingRandomAccess( idToInvertedRenderModels, zToTileSpec, idToValue, scale );
		r.setPosition( this );
		return r;
	}
}
