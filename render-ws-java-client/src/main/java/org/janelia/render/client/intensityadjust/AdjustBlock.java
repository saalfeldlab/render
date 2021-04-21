package org.janelia.render.client.intensityadjust;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.SolveTools;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.render.client.solver.visualize.VisualizeTools;

import fit.polynomial.QuadraticFunction;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.ProfilePlot;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class AdjustBlock {

	public static List< Pair<AffineModel2D,MinimalTileSpec> > getData( final int z, final RenderDataClient renderDataClient, final String stack ) throws IOException
	{
		List< Pair<AffineModel2D,MinimalTileSpec> > data = new ArrayList<>();

		final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, (double)z);
		resolvedTiles.resolveTileSpecs();

		for ( final TileSpec tileSpec : resolvedTiles.getTileSpecs() )
		{
			final AffineModel2D lastTransform = SolveTools.loadLastTransformFromSpec( tileSpec );
			data.add( new ValuePair<>( lastTransform, new MinimalTileSpec( tileSpec ) ) );
		}

		return data;
	}

	public static FloatProcessor correct( final ImageProcessorWithMasks imp, final boolean debug )
	{
		double[] avg = new double[ imp.ip.getWidth() ];
		final ArrayList< Point > points = new ArrayList<>();

		for ( int x = 0; x < avg.length; ++x )
		{
			int count = 0;

			for ( int y = 0; y < imp.ip.getHeight(); ++y )
			{
				final double value = imp.ip.getf(x, y);
				if ( value >= 10 && value <= 245 )
				{
					avg[ x ] += value;
					++count;
				}
			}

			avg[ x ] /= (double)count;
			points.add( new Point( new double[] { x, avg[ x ] } ) );
		}

		if ( debug )
		{
			ImagePlus line = ImageJFunctions.wrapFloat( Views.addDimension( ArrayImgs.doubles( avg, avg.length ), 0, 0 ), "" );
			line.setRoi(new Line(0,0,avg.length,0));
			new ProfilePlot( line ).createWindow();
		}

		try
		{
			QuadraticFunction q = new QuadraticFunction();
			q.fitFunction(points);

			double avgFit = 0.0;

			double[] fit = new double[ avg.length ];
			double[] distance = new double[ avg.length ];

			for ( int x = 0; x < avg.length; ++x )
			{
				fit[ x ] = q.predict( x );
				distance[ x ] = avg[ x ] - fit[ x ];

				avgFit += fit[ x ];
			}

			avgFit /= (double)fit.length;

			double[] correctedAvg = new double[ avg.length ];
			final FloatProcessor corrected = new FloatProcessor( imp.ip.getWidth(), imp.ip.getHeight() );

			for ( int x = 0; x < avg.length; ++x )
			{
				final double correction = ( fit[ x ] - avgFit );

				correctedAvg[ x ] = avg[ x ] - correction;

				for ( int y = 0; y < imp.ip.getHeight(); ++y )
					corrected.setf(x, y, (float)Math.min( 255, Math.max( 0, imp.ip.getf(x, y) - correction ) ) );
			}

			if ( debug )
			{
				ImagePlus fitImp = ImageJFunctions.wrapFloat( Views.addDimension( ArrayImgs.doubles( fit, fit.length ), 0, 0 ), "" );
				fitImp.setRoi(new Line(0,0,avg.length,0));
				new ProfilePlot( fitImp ).createWindow();
	
				ImagePlus distanceImp = ImageJFunctions.wrapFloat( Views.addDimension( ArrayImgs.doubles( distance, distance.length ), 0, 0 ), "" );
				distanceImp.setRoi(new Line(0,0,avg.length,0));
				new ProfilePlot( distanceImp ).createWindow();
	
				ImagePlus correctedImp = ImageJFunctions.wrapFloat( Views.addDimension( ArrayImgs.doubles( correctedAvg, correctedAvg.length ), 0, 0 ), "" );
				correctedImp.setRoi(new Line(0,0,avg.length,0));
				new ProfilePlot( correctedImp ).createWindow();
			}

			return corrected;
		}
		catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;
	}

	public static void fuseAndFixOverlaps(
			final Interval interval,
			final List<Pair<AffineModel2D,MinimalTileSpec>> data,
			final List<Pair<ByteProcessor, FloatProcessor>> corrected )
	{
		// we need to go from inside to outside adjusting avg and stdev of the overlapping areas
		ArrayList< Pair< Integer, Integer > > inputOrder = new ArrayList<>();

		for ( int i = 0; i < data.size(); ++i )
			inputOrder.add( new ValuePair<>( data.get( i ).getB().getImageCol(), i ) );

		// sort by column order
		Collections.sort( inputOrder, (o1, o2 ) -> o1.getA().compareTo( o2.getA() ) );

		ArrayList< Integer > order = new ArrayList<>();

		// [1,2,3,4] -> 3
		// [1,2,4] -> 2
		// [1,4] -> 4
		// [1] -> 1
		while( inputOrder.size() > 0 )
		{
			final int index = inputOrder.size() / 2;
			order.add( inputOrder.get( index ).getB() ); // just remember the index
			inputOrder.remove( index );
		}

		// order maps to [add, mul]
		final HashMap< Integer, double[] > adjustStatistics = new HashMap<>();

		// first tile is not adjusted for overlap (add 0, mul with 1)
		adjustStatistics.put( order.get( 0 ), new double[] { 0, 1 } );

		for ( int o = 1; o < order.size(); ++o )
		{
			final int i = order.get( o );

			final AffineModel2D model = data.get( i ).getA();
			final MinimalTileSpec tileSpec = data.get( i ).getB();
			final RealInterval boundingBox = boundingBox( 0,0,corrected.get( i ).getB().getWidth() - 1,corrected.get( i ).getB().getHeight() - 1, model );
			final RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( i ).getB() ) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			final RealRandomAccessible<UnsignedByteType> interpolantMask = Views.interpolate( Views.extendZero( (RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( i ).getA() ) ) ), new NLinearInterpolatorFactory() );

			// which one do we overlap with that already has statistics
			for ( int q = 0; q < order.size(); ++q )
			{
				if ( o == q )
					continue;

				final int j = order.get( q );

				// it makes sense to look if we overlap
				if ( adjustStatistics.containsKey( j ) )
				{
					final AffineModel2D modelQ = data.get( j ).getA();
					final MinimalTileSpec tileSpecQ = data.get( j ).getB();
					final RealInterval boundingBoxQ = boundingBox( 0,0,corrected.get( j ).getB().getWidth() - 1,corrected.get( j ).getB().getHeight() - 1, modelQ );

					System.out.println( boundingBox );
					System.out.println( boundingBoxQ );

					if ( intersects( boundingBox, boundingBoxQ ) )
					{
						System.out.println( "Testing " + tileSpec.getImageCol() + " vs " + tileSpecQ.getImageCol() );
					}
				}
			}
		}

		System.exit( 0 );
	}

	public static boolean intersects( final RealInterval intervalA, final RealInterval intervalB )
	{
		final RealInterval intersection = Intervals.intersect( intervalA, intervalB );

		for ( int d = 0; d < intersection.numDimensions(); ++d )
			if ( intersection.realMax( d ) <= intersection.realMin( d ) )
				return false;

		return true;
	}

	public static RealInterval boundingBox( final int minX, final int minY, final int maxX, final int maxY, final AffineModel2D model )
	{
		final double[] tmpMin = new double[ 2 ];
		final double[] tmpMax = new double[ 2 ];

		tmpMin[ 0 ] = 0;
		tmpMin[ 1 ] = 0;
		tmpMax[ 0 ] = maxX;
		tmpMax[ 1 ] = maxY;

		model.estimateBounds( tmpMin, tmpMax );

		return new FinalRealInterval( tmpMin, tmpMax );
	}

	public static void main( String[] args ) throws IOException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_BR"; //"flyem";
		String project = "Sec37"; //"Z0419_25_Alpha3";
		String stack = "v2_acquire_trimmed_sp1_adaptive"; //"v1_acquire_sp_nodyn_v2";

		final RenderDataClient renderDataClient = new RenderDataClient(baseUrl, owner, project );
		final StackMetaData meta =  renderDataClient.getStackMetaData( stack );
		//final StackMetaData meta = RenderTools.openStackMetaData(baseUrl, owner, project, stack);
		final Interval interval = RenderTools.stackBounds( meta );

		final int minZ = 5000;
		final int maxZ = 5000;
		final double scale = 1.0;

		new ImageJ();

		for ( int z = minZ; z <= maxZ; ++z )
		{
			List<Pair<AffineModel2D,MinimalTileSpec>> data = getData(z, renderDataClient, stack);
			List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();

			for ( final Pair<AffineModel2D,MinimalTileSpec> tile : data )
			{
				System.out.println( "Processing z=" + tile.getB().getZ() + ", tile=" + tile.getB().getImageCol() );
				final ImageProcessorWithMasks imp = VisualizeTools.getImage( tile.getB(), scale );
				corrected.add( new ValuePair<>( (ByteProcessor)imp.mask, correct(imp, false) ) );
				
				//new ImagePlus( "i", imp.ip ).show();
				//new ImagePlus( "m", imp.mask ).show();
				//new ImagePlus( "c", corrected.get( corrected.size() - 1).getB() ).show();
				//break;
			}

			fuseAndFixOverlaps(interval, data, corrected);
			
		}

		/*
		
		final ImagePlus imp1 = new ImagePlus( project + "-" + stack , imagestack );

		Calibration cal = new Calibration();
		cal.xOrigin = -(int)interval.min(0);
		cal.yOrigin = -(int)interval.min(1);
		cal.zOrigin = -minZ;
		cal.pixelWidth = 1.0/scale;
		cal.pixelHeight = 1.0/scale;
		cal.pixelDepth = 1.0;
		imp1.setCalibration( cal );
		*/
	}
}
