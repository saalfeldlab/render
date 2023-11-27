package org.janelia.render.client.solver.visualize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.janelia.alignment.spec.TileSpec;

import mpicbg.models.AffineModel2D;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Positionable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPositionable;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class VisualizingRandomAccessibleInterval implements RandomAccessibleInterval< FloatType >
{
	final int n = 3;
	final Interval interval;

	final HashMap<String, AffineModel2D> idToInvertedRenderModels;
	final HashMap<Integer, ArrayList<Pair<String, TileSpec>>> zToTileSpec; // at full resolution
	final HashMap<String, Float> idToValue;
	final double[] scale;

	public VisualizingRandomAccessibleInterval(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, TileSpec> idToTileSpec,
			final HashMap<String, Float> idToValue,
			final double[] scale)
	{
		this.scale = scale;
		this.idToValue = idToValue;

		this.idToInvertedRenderModels = new HashMap<>();

		this.interval = computeInterval( idToModels, idToTileSpec, idToInvertedRenderModels, scale );

		// build the lookup z to tilespec
		this.zToTileSpec = new HashMap<>(); 

		for ( final String tileId : idToInvertedRenderModels.keySet() )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			final int z = (int)Math.round( tileSpec.getZ() );
			zToTileSpec.putIfAbsent(z, new ArrayList<>());
			zToTileSpec.get( z ).add( new ValuePair<>( tileId, tileSpec ) );
		}
	}

	@Override
	public RandomAccess<FloatType> randomAccess()
	{
		return new VisualizingRandomAccess(idToInvertedRenderModels, zToTileSpec, idToValue, scale);
	}

	@Override
	public RandomAccess<FloatType> randomAccess(final Interval interval)
	{
		return randomAccess();
	}

	@Override
	public int numDimensions() { return n; }

	@Override
	public long min( final int d ) { return interval.min( d ); }

	@Override
	public void min( final long[] min ) { interval.min( min ); }

	@Override
	public void min( final Positionable min ) { interval.min( min ); }

	@Override
	public long max( final int d ) { return interval.max( d ); }

	@Override
	public void max( final long[] max ) { interval.max( max ); }

	@Override
	public void max( final Positionable max )  { interval.max( max ); }

	@Override
	public double realMin( final int d ) { return interval.realMin( d ); }

	@Override
	public void realMin( final double[] min ) { interval.realMin( min ); }

	@Override
	public void realMin( final RealPositionable min ) { interval.realMin( min ); }

	@Override
	public double realMax( final int d ) { return interval.realMax( d ); }

	@Override
	public void realMax( final double[] max ) { interval.realMax( max ); }

	@Override
	public void realMax( final RealPositionable max ) { interval.realMax( max ); }

	@Override
	public void dimensions( final long[] dimensions ) { interval.dimensions( dimensions ); }

	@Override
	public long dimension( final int d ) { return interval.dimension( d ); }

	/**
	 * @param idToModels - map from all tileIds to their models
	 * @param idToTileSpec - map from all tileIds to their TileSpec
	 * @param scale - the 3d scaling
	 * 
	 * @return the bounding box of all rendered tiles
	 */
	public static Interval computeInterval(
			final Map<String, AffineModel2D> idToModels,
			final Map<String, TileSpec> idToTileSpec,
			final double[] scale )
	{
		return computeInterval( idToModels, idToTileSpec, null, scale );
	}

	/**
	 * @param idToModels - map from all tileIds to their models
	 * @param idToTileSpec - map from all tileIds to their TileSpec
	 * @param idToInvertedRenderModels - will be populated if not null
	 * @param scale - the 3d scaling
	 * 
	 * @return the bounding box of all rendered tiles
	 */
	public static Interval computeInterval(
			final Map<String, AffineModel2D> idToModels,
			final Map<String, TileSpec> idToTileSpec,
			final Map<String, AffineModel2D> idToInvertedRenderModels,
			final double[] scale )
	{
		final double[] min = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

		final double[] tmpMin = new double[ 2 ];
		final double[] tmpMax = new double[ 2 ];

		final AffineModel2D scaleModel = new AffineModel2D();
		scaleModel.set( scale[ 0 ], 0, 0, scale[ 1 ], 0, 0 );

		// get bounding box
		for ( final String tileId : idToModels.keySet() )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			min[ 2 ] = Math.min( min[ 2 ], tileSpec.getZ() * scale[ 2 ] );
			max[ 2 ] = Math.max( max[ 2 ], tileSpec.getZ() * scale[ 2 ] );

			final int w = tileSpec.getWidth();
			final int h = tileSpec.getHeight();

			final AffineModel2D model = idToModels.get( tileId ).copy();

			// scale the actual transform down to the scale level we want to render in
			model.preConcatenate( scaleModel );

			tmpMin[ 0 ] = 0;
			tmpMin[ 1 ] = 0;
			tmpMax[ 0 ] = w - 1;
			tmpMax[ 1 ] = h - 1;

			model.estimateBounds( tmpMin, tmpMax );

			min[ 0 ] = Math.min( min[ 0 ], Math.min( tmpMin[ 0 ], tmpMax[ 0 ] ) );
			max[ 0 ] = Math.max( max[ 0 ], Math.max( tmpMin[ 0 ], tmpMax[ 0 ] ) );

			min[ 1 ] = Math.min( min[ 1 ], Math.min( tmpMin[ 1 ], tmpMax[ 1 ] ) );
			max[ 1 ] = Math.max( max[ 1 ], Math.max( tmpMin[ 1 ], tmpMax[ 1 ] ) );

			if ( idToInvertedRenderModels != null )
				idToInvertedRenderModels.put( tileId, model.createInverse() );
		}

		//System.out.println( "x: " + min[ 0 ] + " >>> " + max[ 0 ] );
		//System.out.println( "y: " + min[ 1 ] + " >>> " + max[ 1 ] );
		//System.out.println( "z: " + min[ 2 ] + " >>> " + max[ 2 ] );

		final long[] minI = new long[ 3 ];
		final long[] maxI = new long[ 3 ];
		final long[] dimI = new long[ 3 ];

		for ( int d = 0; d < minI.length; ++d )
		{
			minI[ d ] = Math.round( Math.floor( min[ d ] ) );
			maxI[ d ] = Math.round( Math.ceil( max[ d ] ) );
			dimI[ d ] = maxI[ d ] - minI[ d ] + 1;
		}

		//System.out.println( "BB x: " + minI[ 0 ] + " >>> " + maxI[ 0 ] + ", d=" + dimI[ 0 ] );
		//System.out.println( "BB y: " + minI[ 1 ] + " >>> " + maxI[ 1 ] + ", d=" + dimI[ 1 ]);
		//System.out.println( "BB z: " + minI[ 2 ] + " >>> " + maxI[ 2 ] + ", d=" + dimI[ 2 ]);

		return new FinalInterval( minI, maxI );
	}
}
