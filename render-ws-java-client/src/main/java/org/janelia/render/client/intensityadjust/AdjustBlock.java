package org.janelia.render.client.intensityadjust;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.SolveTools;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.render.client.solver.visualize.VisualizeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fit.polynomial.QuadraticFunction;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Line;
import ij.gui.ProfilePlot;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.AffineModel2D;
import mpicbg.models.Point;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import mpicbg.util.RealSum;
import net.imglib2.Cursor;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
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

	/**
	 * Fits a quadratic function along X and removes the difference, independently for each image
	 * 
	 * @param imp
	 * @param debug
	 * @return
	 */
	public static FloatProcessor correct( final ImageProcessorWithMasks imp, final boolean debug )
	{
		double[] avg = new double[ imp.ip.getWidth() ];
		final ArrayList< Point > points = new ArrayList<>();

		// use only 70% of the pixels in the middle
		final int startY = Math.round( imp.ip.getHeight() * 0.15f );
		final int endY = Math.round( imp.ip.getHeight() * 0.85f );

		for ( int x = 0; x < avg.length; ++x )
		{
			//final ArrayList< Float > median = new ArrayList<>();

			int count = 0;

			for ( int y = startY; y <= endY; ++y )
			{
				final double value = imp.ip.getf(x, y);
				if ( value >= 10 && value <= 245 )
				{
					//median.add( (float)value );
					avg[ x ] += value;
					++count;
				}
			}

			avg[ x ] /= (double)count;

			//Collections.sort( median );
			//avg[ x ] = median.get( median.size() / 2 );

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
			/*
			final double[] fit = avg.clone();
			RandomAccessibleInterval<DoubleType> img = ArrayImgs.doubles( fit, avg.length );
			Gauss3.gauss( 500, Views.extendBorder( img ), img );

			double avgFit = 0.0;

			for ( int x = 0; x < avg.length; ++x )
			{
				avgFit += fit[ x ];
			}
			
			avgFit /= (double)fit.length;
			*/

			//HigherOrderPolynomialFunction q = new HigherOrderPolynomialFunction( 3 );
			QuadraticFunction q = new QuadraticFunction();
			q.fitFunction(points);

			double avgFit = 0.0;

			double[] fit = new double[ avg.length ];

			for ( int x = 0; x < avg.length; ++x )
			{
				fit[ x ] = q.predict( x );

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

				ImagePlus correctedImp = ImageJFunctions.wrapFloat( Views.addDimension( ArrayImgs.doubles( correctedAvg, correctedAvg.length ), 0, 0 ), "" );
				correctedImp.setRoi(new Line(0,0,avg.length,0));
				new ProfilePlot( correctedImp ).createWindow();
			}

			return corrected;
		}
		catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;
	}

	public static HashMap< Integer, double[] > computeAdjustments(
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

		// order maps to [sub, mul, add]
		final HashMap< Integer, double[] > adjustments = new HashMap<>();

		// first tile is not adjusted for overlap (add 0, mul with 1, sub 0)
		adjustments.put( order.get( 0 ), new double[] { 0, 1, 0 } );

		for ( int o = 1; o < order.size(); ++o )
		{
			final int i = order.get( o );

			final AffineModel2D model = data.get( i ).getA();
			final AffineTransform2D affine = toImgLib( model );
			final MinimalTileSpec tileSpec = data.get( i ).getB();
			final RealInterval boundingBox = boundingBox( 0,0,corrected.get( i ).getB().getWidth() - 1,corrected.get( i ).getB().getHeight() - 1, model );

			final RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( i ).getB() ) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			final RealRandomAccessible<FloatType> interpolantMask = Views.interpolate( Views.extendZero( Converters.convert( ((RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( i ).getA()) )), (in,out) -> out.setReal( in.getRealFloat() ), new FloatType() ) ), new NLinearInterpolatorFactory<>() );

			final RealRandomAccess<FloatType> im = RealViews.affine( interpolant, affine ).realRandomAccess();
			final RealRandomAccess<FloatType> ma = RealViews.affine( interpolantMask, affine ).realRandomAccess();

			// which one do we overlap with that already has statistics
			for ( int q = 0; q < order.size(); ++q )
			{
				if ( o == q )
					continue;

				final int j = order.get( q );

				// it makes sense to look if we overlap
				if ( adjustments.containsKey( j ) )
				{
					final double[] adjust = adjustments.get( j );
					final AffineModel2D modelQ = data.get( j ).getA();
					final AffineTransform2D affineQ = toImgLib( modelQ );
					final RealInterval boundingBoxQ = boundingBox( 0,0,corrected.get( j ).getB().getWidth() - 1,corrected.get( j ).getB().getHeight() - 1, modelQ );

					if ( intersects( boundingBox, boundingBoxQ ) )
					{
						//System.out.println( "Testing " + tileSpec.getImageCol() + " vs " + tileSpecQ.getImageCol() );

						final RealRandomAccessible<FloatType> interpolantQ = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( j ).getB() ) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
						final RealRandomAccessible<FloatType> interpolantMaskQ = Views.interpolate( Views.extendZero( Converters.convert( ((RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( j ).getA()) )), (in,out) -> out.setReal( in.getRealFloat() ), new FloatType() ) ), new NLinearInterpolatorFactory<>() );

						final RealRandomAccess<FloatType> imQ = RealViews.affine( interpolantQ, affineQ ).realRandomAccess();
						final RealRandomAccess<FloatType> maQ = RealViews.affine( interpolantMaskQ, affineQ ).realRandomAccess();

						// iterate over the intersection of both tiles
						final Interval testInterval = Intervals.smallestContainingInterval( Intervals.intersect( boundingBox, boundingBoxQ ) );

						final IntervalIterator it = new IntervalIterator( testInterval );
						final long[] l = new long[ testInterval.numDimensions() ];

						RealSum sumO = new RealSum();
						RealSum sumQ = new RealSum();
						int count = 0;

						while ( it.hasNext() )
						{
							it.fwd();
							it.localize( l );

							ma.setPosition( l );
							if ( ma.get().get() < 254.99 )
								continue;

							maQ.setPosition( l );
							if ( maQ.get().get() < 254.99 )
								continue;

							im.setPosition( l );
							sumO.add( im.get().get() );

							imQ.setPosition( l );
							sumQ.add( ( ( imQ.get().get() - adjust[ 0 ] ) * adjust[ 1 ] ) + adjust[ 2 ] );

							++count;
						}

						if ( count > 0 )
						{
							final double avgO = sumO.getSum() / (double)count;
							final double avgQ = sumQ.getSum() / (double)count;

							//System.out.println( count + ": " + avgO + ", " + avgQ );

							sumO = new RealSum();
							sumQ = new RealSum();
							count = 0;
							it.reset();

							while ( it.hasNext() )
							{
								it.fwd();
								it.localize( l );

								ma.setPosition( l );
								if ( ma.get().get() < 254.99 )
									continue;

								maQ.setPosition( l );
								if ( maQ.get().get() < 254.99 )
									continue;

								im.setPosition( l );
								sumO.add( Math.pow( im.get().get() - avgO, 2 ) );

								imQ.setPosition( l );
								sumQ.add( Math.pow( ( ( ( imQ.get().get() - adjust[ 0 ] ) * adjust[ 1 ] ) + adjust[ 2 ] ) - avgQ, 2 ) );

								++count;
							}

							final double stDevO = Math.sqrt( sumO.getSum() / (double)count );
							final double stDevQ = Math.sqrt( sumQ.getSum() / (double)count );

							//System.out.println( count + ": " + stDevO + ", " + stDevQ );

							// TODO: there is a mistake if there are more than two overlaps
							// Apply the already computed transformation
							final double mul = stDevQ / stDevO;
							adjustments.put( i, new double[] { avgO, mul, avgQ } );

							LOG.debug("computeAdjustments: adjustment for column {} is: sub {}, mul {}, add {}",
									  tileSpec.getImageCol(), avgO, mul, avgQ);
							break;
						}
					}
				}
			}

			if ( !adjustments.containsKey( i ) )
			{
				LOG.warn("computeAdjustments: COULD NOT ADJUST column {}, setting to identity",
						 tileSpec.getImageCol());
				adjustments.put( i, new double[] { 0, 1, 0 } );
			}
		}

		return adjustments;
	}

	public static RandomAccessibleInterval< UnsignedByteType > fuse2d(
			final Interval interval,
			final List<Pair<AffineModel2D,MinimalTileSpec>> data,
			final List<Pair<ByteProcessor, FloatProcessor>> corrected,
			final Map< Integer, double[] > adjustments  )
	{
		// draw
		final RandomAccessibleInterval< UnsignedByteType > slice = Views.translate( ArrayImgs.unsignedBytes( interval.dimension( 0 ), interval.dimension( 1 ) ), interval.min( 0 ), interval.min( 1 ) );

		for ( int i = 0; i < data.size(); ++i )
		{
			final double[] adjust = adjustments.get( i );
			//System.out.println( i + " " + Util.printCoordinates( adjust ) );

			final AffineModel2D model = data.get( i ).getA();

			final RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( i ).getB() ) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			final RealRandomAccessible<FloatType> interpolantMask = Views.interpolate( Views.extendZero( Converters.convert( ((RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", corrected.get( i ).getA()) )), (in,o) -> o.setReal( in.getRealFloat() ), new FloatType() ) ), new NLinearInterpolatorFactory<>() );

			//final IterableInterval< UnsignedByteType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
			final Cursor< UnsignedByteType > c = Views.iterable( slice ).cursor();

			final AffineTransform2D affine = toImgLib( model );

			final Cursor< FloatType > cSrc = Views.interval( RealViews.affine( interpolant, affine ), slice ).cursor();
			final Cursor< FloatType > cMask = Views.interval( RealViews.affine( interpolantMask, affine ), slice ).cursor();

			// Math.min( 255, Math.max( 0, 

			while ( c.hasNext() )
			{
				c.fwd();
				cMask.fwd();
				cSrc.fwd();
				if (cMask.get().get() >= 254.99 ) {
					final FloatType srcType = cSrc.get();
					final float value = (float)( ((srcType.get() - adjust[ 0 ]) * adjust[ 1 ] ) + adjust[ 2 ] );
					if (value >= 0) {
						final UnsignedByteType type = c.get();
						final float currentValue = type.get();
						if ( currentValue > 0 )
							type.setReal( Math.min( 255, Math.max( 0, ( value + currentValue ) / 2 ) ) );
						else
							type.setReal( Math.min( 255, Math.max( 0, value ) ) );
					}
				}
			}
		}

		return slice;
	}

	public static AffineTransform2D toImgLib( final AffineModel2D model )
	{
		final AffineTransform2D affine = new AffineTransform2D();
		double[] array = new double[6];
		model.toArray( array );
		affine.set( array[0], array[2], array[4], array[1], array[3], array[5] );
		return affine;
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


	public static RandomAccessibleInterval<UnsignedByteType> renderIntensityAdjustedSlice(final String stack,
																						  final RenderDataClient renderDataClient,
																						  final Interval interval,
																						  final double scale,
																						  final boolean cacheOnDisk,
																						  final int z)
			throws IOException {

		final List<Pair<AffineModel2D,MinimalTileSpec>> data = getData(z, renderDataClient, stack);
		final List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();

		for ( final Pair<AffineModel2D,MinimalTileSpec> tile : data )
		{
			final MinimalTileSpec minimalTileSpec = tile.getB();

			LOG.debug("renderIntensityAdjustedSlice: processing tile {} in column {}",
					  minimalTileSpec.getTileId(), minimalTileSpec.getImageCol());

			final ImageProcessorWithMasks imp = VisualizeTools.getImage(minimalTileSpec, scale, cacheOnDisk);
			corrected.add( new ValuePair<>( (ByteProcessor)imp.mask, correct(imp, false) ) );

			//new ImagePlus( "i", imp.ip ).show();
			//new ImagePlus( "m", imp.mask ).show();
			//new ImagePlus( "c", corrected.get( corrected.size() - 1).getB() ).show();
			//SimpleMultiThreading.threadHaltUnClean();
		}

		//SimpleMultiThreading.threadHaltUnClean();

		// order maps to [sub, mul, add]
		final HashMap< Integer, double[] > adjustments = computeAdjustments( data, corrected );

		return fuse2d(interval, data, corrected, adjustments);
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

		final int minZ = 20000;
		final int maxZ = 20000;
		final double scale = 1.0; // only full res supported right now
		final boolean cacheOnDisk = true;

		new ImageJ();

		final ImageStack stack3d = new ImageStack( (int)interval.dimension( 0 ), (int)interval.dimension( 1 ) );

		for ( int z = minZ; z <= maxZ; ++z )
		{
			final RandomAccessibleInterval<UnsignedByteType> slice =
					renderIntensityAdjustedSlice(stack, renderDataClient, interval, scale, cacheOnDisk, z);
			stack3d.addSlice( ImageJFunctions.wrap( slice, "" ).getProcessor() );
		}

		final ImagePlus imp1 = new ImagePlus( project + "_" + stack, stack3d );

		Calibration cal = new Calibration();
		cal.xOrigin = -(int)interval.min(0);
		cal.yOrigin = -(int)interval.min(1);
		cal.zOrigin = -minZ;
		cal.pixelWidth = 1.0/scale;
		cal.pixelHeight = 1.0/scale;
		cal.pixelDepth = 1.0;
		imp1.setCalibration( cal );

		imp1.show();
	}

	private static final Logger LOG = LoggerFactory.getLogger(AdjustBlock.class);

}
