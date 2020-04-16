package org.janelia.render.client.solver;

import java.util.ArrayList;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class MultiResolutionSource implements Source< VolatileFloatType >
{
	final String name;
	final ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > multiRes;

	public MultiResolutionSource(
			final ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > multiRes,
			final String name )
	{
		this.multiRes = multiRes;
		this.name = name;
	}

	@Override
	public boolean isPresent( final int t )
	{
		if ( t == 0 )
			return true;
		else
			return false;
	}

	@Override
	public RandomAccessibleInterval< VolatileFloatType > getSource( final int t, final int level )
	{
		if ( t != 0 )
			return null;
		else
			return multiRes.get( level ).getA();
	}

	@Override
	public RealRandomAccessible< VolatileFloatType > getInterpolatedSource( final int t, final int level, final Interpolation method )
	{
		if ( t != 0 )
			return null;
		else if ( method == Interpolation.NEARESTNEIGHBOR )
			return Views.interpolate( Views.extendZero( multiRes.get( level ).getA() ), new NearestNeighborInterpolatorFactory<>() );
		else
			return Views.interpolate( Views.extendZero( multiRes.get( level ).getA() ), new NLinearInterpolatorFactory<>() );
	}

	@Override
	public void getSourceTransform( final int t, final int level, final AffineTransform3D transform )
	{
		if ( t != 0 )
			return;
		else
			transform.set( multiRes.get( level ).getB() );
	}

	@Override
	public VolatileFloatType getType()
	{
		return new VolatileFloatType();
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public VoxelDimensions getVoxelDimensions()
	{
		return new FinalVoxelDimensions( "um", 1.0, 1.0, 1.0 );
	}

	@Override
	public int getNumMipmapLevels()
	{
		return multiRes.size();
	}
}
