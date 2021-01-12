package org.janelia.render.client.solver.visualize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.render.client.solver.MinimalTileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvStackSource;
import bdv.viewer.DisplayMode;
import mpicbg.models.AffineModel2D;
import mpicbg.util.RealSum;
import net.imglib2.util.Pair;

public class ErrorTools
{
	public static enum ErrorFilter{ ALL, CROSS_LAYER_ONLY, MONTAGE_LAYER_ONLY };
	public static enum ErrorType{ MIN, AVG, MAX };

	public static int problematicRegionRange = 50;

	public static class Errors
	{
		final HashMap<String, Float> idToMinError = new HashMap<>();
		final HashMap<String, Float> idToAvgError = new HashMap<>();
		final HashMap<String, Float> idToMaxError = new HashMap<>();

		double minMinError = Double.MAX_VALUE;
		double avgMinError = 0;
		double stdDevMinError = 0;
		double maxMinError = -Double.MAX_VALUE;
		String maxMinTileId = "";

		double minAvgError = Double.MAX_VALUE;
		double avgAvgError = 0;
		double stdDevAvgError = 0;
		double maxAvgError = -Double.MAX_VALUE;
		String maxAvgTileId = "";

		double minMaxError = Double.MAX_VALUE;
		double avgMaxError = 0;
		double stdDevMaxError = 0;
		double maxMaxError = -Double.MAX_VALUE;
		String maxMaxTileId = "";

		long countUnassigned = 0;
	}

	public static Errors computeErrors(
			final Map< String, List< Pair< String, Double > > > idToErrors,
			final Map< String, MinimalTileSpec > idToTileSpec,
			final ErrorFilter filter )
	{
		final Errors err = new Errors();

		final RealSum avgMinError = new RealSum();
		final RealSum avgAvgError = new RealSum();
		final RealSum avgMaxError = new RealSum();

		long countAssigned = 0;

		for ( final String tileId : idToErrors.keySet() )
		{
			final double[] errors = errors( tileId, idToErrors, idToTileSpec, filter );

			err.idToMinError.put( tileId, (float)errors[ 0 ] );
			err.idToAvgError.put( tileId, (float)errors[ 1 ] );
			err.idToMaxError.put( tileId, (float)errors[ 2 ] );

			if ( errors[ 0 ] != -1 ) // not connected according to ErrorFilter
			{
				err.minMinError = Math.min( err.minMinError, errors[ 0 ] );
				avgMinError.add( errors[ 0 ] );
	
				if ( errors[ 0 ] > err.maxMinError )
				{
					err.maxMinTileId = tileId;
					err.maxMinError = errors[ 0 ];
				}

				err.minAvgError = Math.min( err.minAvgError, errors[ 1 ] );
				avgAvgError.add( errors[ 1 ] );
	
				if ( errors[ 1 ] > err.maxAvgError )
				{
					err.maxAvgTileId = tileId;
					err.maxAvgError = errors[ 1 ];
				}

				err.minMaxError = Math.min( err.minMaxError, errors[ 2 ] );
				avgMaxError.add( errors[ 2 ] );
	
				if ( errors[ 2 ] > err.maxMaxError )
				{
					err.maxMaxTileId = tileId;
					err.maxMaxError = errors[ 2 ];
				}

				++countAssigned;
			}
			else
			{
				++err.countUnassigned;
			}
		}

		if ( countAssigned > 0 )
		{
			err.avgMinError = avgMinError.getSum() / countAssigned;
			err.avgAvgError = avgAvgError.getSum() / countAssigned;
			err.avgMaxError = avgMaxError.getSum() / countAssigned;

			final RealSum stdDevMinError = new RealSum();
			final RealSum stdDevAvgError = new RealSum();
			final RealSum stdDevMaxError = new RealSum();

			for ( final String tileId : idToErrors.keySet() )
			{
				final float minErrorTile = err.idToMinError.get( tileId );

				if ( minErrorTile < 0 )
					continue;

				final float avgErrorTile = err.idToAvgError.get( tileId );
				final float maxErrorTile = err.idToMaxError.get( tileId );

				stdDevMinError.add( Math.pow( minErrorTile - err.avgMinError, 2 ) );
				stdDevAvgError.add( Math.pow( avgErrorTile - err.avgAvgError, 2 ) );
				stdDevMaxError.add( Math.pow( maxErrorTile - err.avgMaxError, 2 ) );
			}

			err.stdDevMinError = Math.sqrt( stdDevMinError.getSum() / countAssigned );
			err.stdDevAvgError = Math.sqrt( stdDevAvgError.getSum() / countAssigned );
			err.stdDevMaxError = Math.sqrt( stdDevMaxError.getSum() / countAssigned );
		}
		else
		{
			err.maxMinTileId = err.maxAvgTileId = err.maxMaxTileId = "";
			err.avgMinError = err.avgAvgError = err.avgMaxError = -1;
			err.stdDevMinError = err.stdDevAvgError = err.stdDevMaxError = -1;
		}

		LOG.info( "MIN errors:" );
		LOG.info( "min=" + err.minMinError + ", avg=" + err.avgMinError + " (stdev=" + err.stdDevMinError + "), max=" + err.maxMinError + " (tile=" + err.maxMinTileId + ")" );

		LOG.info( "AVG errors:" );
		LOG.info( "min=" + err.minAvgError + ", avg=" + err.avgAvgError + " (stdev=" + err.stdDevAvgError + "), max=" + err.maxAvgError + " (tile=" + err.maxAvgTileId + ")" );

		LOG.info( "MAX errors:" );
		LOG.info( "min=" + err.minMaxError + ", avg=" + err.avgMaxError + " (stdev=" + err.stdDevMaxError + "), max=" + err.maxMaxError + " (tile=" + err.maxMaxTileId + ")" );

		if ( countAssigned > 0 )
		{
			LOG.info( "\nmax MIN error connections of tileId "  + err.maxMinTileId );
			for ( final Pair< String, Double > error : idToErrors.get( err.maxMinTileId ) )
				LOG.info( error.getA() + ": " + error.getB() );
	
			LOG.info( "\nmax AVG error connections of tileId "  + err.maxAvgTileId );
			for ( final Pair< String, Double > error : idToErrors.get( err.maxAvgTileId ) )
				LOG.info( error.getA() + ": " + error.getB() );
	
			LOG.info( "\nmax MAX error connections of tileId "  + err.maxMaxTileId );
			for ( final Pair< String, Double > error : idToErrors.get( err.maxMaxTileId ) )
				LOG.info( error.getA() + ": " + error.getB() );
		}

		return err;
	}

	public static BdvStackSource< ? > renderPotentialProblemAreas(
			BdvStackSource< ? > source,
			final Errors err,
			final ErrorType errorType,
			final double significance,
			final HashMap< String, AffineModel2D > idToModel,
			final HashMap< String, MinimalTileSpec > idToTileSpec )
	{
		// the actual values to display
		final HashMap< String, Float > idToRegion = new HashMap<>();

		// to later find the neighbors
		final HashMap< Integer, List< MinimalTileSpec > > zToTileSpec = new HashMap<>();
		final HashMap< Integer, List< MinimalTileSpec > > zWithOutliers = new HashMap<>();

		for ( final String tileId : idToTileSpec.keySet() )
		{
			final MinimalTileSpec ts = idToTileSpec.get( tileId );
			final int z = (int)Math.round( ts.getZ() );

			final double avgError, stDevError, error;
			
			switch ( errorType )
			{
			case MIN:
				error = err.idToMinError.get( tileId );
				avgError = err.avgMinError;
				stDevError = err.stdDevMinError;
				break;
			case MAX:
				error = err.idToMaxError.get( tileId );
				avgError = err.avgMaxError;
				stDevError = err.stdDevMaxError;
				break;
			default:
				error = err.idToAvgError.get( tileId );
				avgError = err.avgAvgError;
				stDevError = err.stdDevAvgError;
				break;
			}

			if ( error > avgError + significance * stDevError )
			{
				idToRegion.put( tileId, 1.0f );
				zWithOutliers.putIfAbsent( z, new ArrayList<>() );
				zWithOutliers.get( z ).add( ts );
			}
			else
			{
				idToRegion.put( tileId, 0.0f );
			}

			zToTileSpec.putIfAbsent( z, new ArrayList<>() );
			zToTileSpec.get( z ).add( ts );
		}

		for ( final int z : zWithOutliers.keySet() )
		{
			for ( final MinimalTileSpec ts : zWithOutliers.get( z ) )
			{
				final int col = ts.getImageCol();

				for ( int d = 1; d < problematicRegionRange; ++d )
				{
					if ( zToTileSpec.containsKey( z + d ))
						for ( final MinimalTileSpec ts2 : zToTileSpec.get( z + d ) )
							if ( ts2 != null && ts2.getImageCol() == col )
								idToRegion.put( ts2.getTileId(), Math.max( idToRegion.get( ts2.getTileId() ), (problematicRegionRange - d) / (float)problematicRegionRange ) );

					if ( zToTileSpec.containsKey( z - d ))
						for ( final MinimalTileSpec ts2 : zToTileSpec.get( z - d ) )
							if ( ts2 != null && ts2.getImageCol() == col )
								idToRegion.put( ts2.getTileId(), Math.max( idToRegion.get( ts2.getTileId() ), (problematicRegionRange - d) / (float)problematicRegionRange ) );
				}
			}
		}

		source = VisualizeTools.visualizeMultiRes( source, "potential problem regions (" + significance + ")", idToModel, idToTileSpec, idToRegion, 1, 128, 2, Runtime.getRuntime().availableProcessors() );
		source.setDisplayRange( 0, 1 );
		source.setDisplayRangeBounds( 0, 1 );

		return source;
	}

	public static BdvStackSource< ? > renderErrors(
			final Errors err,
			final HashMap< String, AffineModel2D > idToModel,
			final HashMap< String, MinimalTileSpec > idToTileSpec )
	{
		return renderErrors( null, err, idToModel, idToTileSpec );
	}

	public static BdvStackSource< ? > renderErrors(
			BdvStackSource< ? > source,
			final Errors err,
			final HashMap< String, AffineModel2D > idToModel,
			final HashMap< String, MinimalTileSpec > idToTileSpec )
	{
		final double maxRange = Math.max( err.maxMinError, Math.max( err.maxAvgError, err.maxMaxError ) );

		source = VisualizeTools.visualizeMultiRes( source, "avg Error", idToModel, idToTileSpec, err.idToAvgError, 1, 128, 2, Runtime.getRuntime().availableProcessors() );
		source.setDisplayRange( 0, err.maxAvgError );
		source.setDisplayRangeBounds( 0, maxRange );

		source = VisualizeTools.visualizeMultiRes( source, "min Error", idToModel, idToTileSpec, err.idToMinError, 1, 128, 2, Runtime.getRuntime().availableProcessors() );
		source.setDisplayRange( 0, err.maxMinError );
		source.setDisplayRangeBounds( 0, maxRange );

		source = VisualizeTools.visualizeMultiRes( source, "max Error", idToModel, idToTileSpec, err.idToMaxError, 1, 128, 2, Runtime.getRuntime().availableProcessors() );
		source.setDisplayRange( 0, err.maxMaxError );
		source.setDisplayRangeBounds( 0, maxRange );

		source.getBdvHandle().getViewerPanel().setDisplayMode( DisplayMode.SINGLE );

		return source;
	}

	public static double[] errors(
			final String tileId,
			final Map< String, List< Pair< String, Double > > > errors,
			final Map< String, MinimalTileSpec > idToTileSpec,
			final ErrorFilter errorFilter )
	{
		final int z = (int)Math.round( idToTileSpec.get( tileId ).getZ() );

		double minError = Double.MAX_VALUE;
		double maxError = -1;
		double avgError = 0;
		int count = 0;

		for ( final Pair< String, Double > error : errors.get( tileId ) )
		{
			if ( errorFilter == ErrorFilter.ALL || 
					( errorFilter == ErrorFilter.CROSS_LAYER_ONLY && z != (int)Math.round( idToTileSpec.get( error.getA() ).getZ() ) ) ||
					( errorFilter == ErrorFilter.MONTAGE_LAYER_ONLY && z == (int)Math.round( idToTileSpec.get( error.getA() ).getZ() ) ) )
				{
					minError = Math.min( minError, error.getB() );
					maxError = Math.max( maxError, error.getB() );
					avgError += error.getB();
					++count;
				}
		}

		if ( count > 0 )
			avgError /= (double)count;
		else
			minError = avgError = maxError = -1;

		return new double[] { minError, avgError, maxError };
	}

	private static final Logger LOG = LoggerFactory.getLogger(ErrorTools.class);
}
