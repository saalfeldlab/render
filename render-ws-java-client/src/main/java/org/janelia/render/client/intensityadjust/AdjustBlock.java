package org.janelia.render.client.intensityadjust;

import static org.janelia.alignment.util.ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.PreloadedImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.intensity.IntensityMatcher;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.visualize.RenderTools;
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
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class AdjustBlock {

	public static List< MinimalTileSpecWrapper > wrapTileSpecs(final ResolvedTileSpecCollection resolvedTiles)
	{
		final List< MinimalTileSpecWrapper > data = new ArrayList<>(resolvedTiles.getTileCount());
		// tile order changes adjustment results, so sort tiles by id to ensure (somewhat) consistent results
		final List<TileSpec> sortedTileSpecs = resolvedTiles.getTileSpecs()
				.stream()
				.sorted(Comparator.comparing(TileSpec::getTileId))
				.collect(Collectors.toList());
		for ( final TileSpec tileSpec : sortedTileSpecs )
		{
			//final AffineModel2D lastTransform = SolveTools.loadLastTransformFromSpec( tileSpec );
			//data.add( new ValuePair<>( lastTransform, new MinimalTileSpec( tileSpec ) ) );
			data.add( new MinimalTileSpecWrapper( tileSpec ) );
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

	public static ImageProcessorWithMasks fuseFinal(
			final RenderParameters sliceRenderParameters,
			final List<MinimalTileSpecWrapper> data1,
			final ArrayList < OnTheFlyIntensity > corrected1,
			final ImageProcessorCache imageProcessorCache )
	{
		// TODO: pass pre-loaded cache in and clear source data so that masks can be cached and reused across z
		final PreloadedImageProcessorCache preloadedImageProcessorCache =
				new PreloadedImageProcessorCache(DEFAULT_MAX_CACHED_PIXELS,
												 false,
												 false);

		for (int i = 0; i < data1.size(); i++) {
			final MinimalTileSpecWrapper wrapper = data1.get(i);

			// this should be a virtual construct
			{
				/*
				final FloatProcessor correctedSource = corrected1.get( i ).computeIntensityCorrectionOnTheFly(imageProcessorCache);
	
				// Need to reset intensity range back to full 8-bit before converting to byte processor!
				correctedSource.setMinAndMax(0, 255);
				final ByteProcessor correctedSource8Bit = correctedSource.convertToByteProcessor();
				*/

				preloadedImageProcessorCache.put(wrapper.getTileImageUrl(), corrected1.get( i ).computeIntensityCorrection8BitOnTheFly(imageProcessorCache) );
			}
		}
		// TODO: this will be bigger than 2^31
		return Renderer.renderImageProcessorWithMasks(sliceRenderParameters, preloadedImageProcessorCache);
	}

	public static ImageProcessorWithMasks fuseFinal(
			final RenderParameters sliceRenderParameters,
			final List<MinimalTileSpecWrapper> data1,
			final List<Pair<ByteProcessor, FloatProcessor>> corrected1 ) // TODO: this will likely cause outofmemory
	{
		// TODO: pass pre-loaded cache in and clear source data so that masks can be cached and reused across z
		final PreloadedImageProcessorCache preloadedImageProcessorCache =
				new PreloadedImageProcessorCache(DEFAULT_MAX_CACHED_PIXELS,
												 false,
												 false);

		for (int i = 0; i < data1.size(); i++) {
			final MinimalTileSpecWrapper wrapper = data1.get(i);
			final FloatProcessor correctedSource = corrected1.get(i).getB();

			// Need to reset intensity range back to full 8-bit before converting to byte processor!
			correctedSource.setMinAndMax(0, 255);
			final ByteProcessor correctedSource8Bit = correctedSource.convertToByteProcessor();
			
			preloadedImageProcessorCache.put(wrapper.getTileImageUrl(), correctedSource8Bit);
		}
		// TODO: this will be bigger than 2^31
		return Renderer.renderImageProcessorWithMasks(sliceRenderParameters, preloadedImageProcessorCache);
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

	public static ArrayList<OnTheFlyIntensity> correctIntensitiesForSliceTiles(
			final List<MinimalTileSpecWrapper> sliceTiles,
			final double renderScale,
			final Integer zDistance,
			final ImageProcessorCache imageProcessorCache,
			final int numCoefficients,
			final IntensityCorrectionStrategy strategy,
			final int numThreads)
			throws InterruptedException, ExecutionException {

		final double neighborWeight = 0.1;
		final int iterations = 2000;

		//final List<Pair<ByteProcessor, FloatProcessor>> corrected = new IntensityMatcher().match(
		return new IntensityMatcher().match(
				sliceTiles,
				renderScale,
				numCoefficients,
				strategy,
				zDistance,
				neighborWeight,
				iterations,
				imageProcessorCache,
				numThreads);
	}

	public static ArrayList<OnTheFlyIntensity> correctIntensitiesForPatchPairs(
			final List<ValuePair<MinimalTileSpecWrapper, MinimalTileSpecWrapper>> patchPairs,
			final double renderScale,
			final ImageProcessorCache imageProcessorCache,
			final int numCoefficients,
			final IntensityCorrectionStrategy strategy,
			final int numThreads)
			throws InterruptedException, ExecutionException {

		final double neighborWeight = 0.1;
		final int iterations = 2000;

		//final List<Pair<ByteProcessor, FloatProcessor>> corrected = new IntensityMatcher().match(
		return new IntensityMatcher().matchPairs(patchPairs,
												 renderScale,
												 numCoefficients,
												 strategy,
												 neighborWeight,
												 iterations,
												 imageProcessorCache,
												 numThreads);
	}

	public static ImageProcessorWithMasks renderIntensityAdjustedSliceGlobalPerSlice(
			final ResolvedTileSpecCollection resolvedTiles,
			final RenderParameters sliceRenderParameters,
			final double renderScale,
			final Integer zDistance,
			final ImageProcessorCache imageProcessorCache,
			final int z,
			final int numCoefficients,
			final IntensityCorrectionStrategy strategy,
			final int numThreads) throws InterruptedException, ExecutionException
	{
		final List<MinimalTileSpecWrapper> tilesForZ = wrapTileSpecs(resolvedTiles);
		//final HashMap< Integer, double[] > adjustments = new HashMap<>();

		//final List<Pair<ByteProcessor, FloatProcessor>> corrected = new IntensityMatcher().match(
		final ArrayList < OnTheFlyIntensity > corrected = correctIntensitiesForSliceTiles(tilesForZ,
																						  renderScale,
																						  zDistance,
																						  imageProcessorCache,
																						  numCoefficients,
																						  strategy,
																						  numThreads);

		// TODO: why is fuseFinal limited to 2^31, how was the same thing done in TrakEM2?
		return fuseFinal(sliceRenderParameters, tilesForZ, corrected, imageProcessorCache);
	}

//	public static RandomAccessibleInterval<UnsignedByteType> renderIntensityAdjustedSliceGauss(final String stack,
//			  final RenderDataClient renderDataClient,
//			  final Interval interval,
//			  final boolean weightening,
//			  final boolean cacheOnDisk,
//			  final int z) throws IOException
//	{
//		final boolean isSec26 = renderDataClient.getUrls().getStackUrlString( "" ).contains( "Sec26" );
//		LOG.debug("renderIntensityAdjustedSliceGauss: isSec26=" + isSec26 );
//
//		final double scale = 0.22;
//		final double[] sigma = new double[] { 0, 50 };
//
//		final List<MinimalTileSpecWrapper> data = getData(z, renderDataClient, stack);
//		final List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();
//		final HashMap< Integer, double[] > adjustments = new HashMap<>();
//
//		int k = -1;
//		for (final Pair<AffineModel2D, MinimalTileSpec> tile : data) {
//			++k;
//			final MinimalTileSpec minimalTileSpec = tile.getB();
//
//			//if (minimalTileSpec.getImageCol() != 0 )
//			//	continue;
//
//			LOG.debug("renderIntensityAdjustedSliceGauss: processing tile {} in column {}", minimalTileSpec.getTileId(),
//					minimalTileSpec.getImageCol());
//
//			if ( weightening )
//			{
//				final ImageProcessorWithMasks imp = VisualizeTools.getImage(minimalTileSpec, scale, cacheOnDisk);
//
//				//new ImagePlus("imp_" + minimalTileSpec.getImageCol(), imp.ip).duplicate().show();
//
//				final FloatProcessor image = imp.ip.convertToFloatProcessor();
//
//				// median filter smoothes resin way more than inside the sample
//				new RankFilters().rank( image, 3, RankFilters.MEDIAN );
//
//				final RandomAccessibleInterval<FloatType> imgA = ArrayImgs.floats((float[]) image.getPixels(),
//						image.getWidth(), image.getHeight());
//				final float[] outP = new float[image.getWidth() * image.getHeight()];
//				final Img<FloatType> out = ArrayImgs.floats(outP, image.getWidth(), image.getHeight());
//
//				Gauss3.gauss(sigma, Views.extendMirrorSingle(imgA), out);
//
//				Cursor<FloatType> ic = Views.flatIterable(imgA).cursor();
//				Cursor<FloatType> pc = Views.flatIterable(out).cursor();
//
//				while (pc.hasNext()) {
//					final FloatType p = pc.next();
//					final FloatType j = ic.next();
//
//					double q = j.get() / p.get();
//					if ( q < 1 )
//						q = 1.0/q;
//					if ( Double.isNaN( q ) || Double.isInfinite( q ) )
//						q = 1.0;
//
//					p.set(Math.max(0, (float) Math.abs( q )));
//				}
//
//				Gauss3.gauss(sigma, Views.extendMirrorSingle(out), out);
//
//				//for (final FloatType t : out)
//				//	t.set((float) (Math.sqrt(t.getRealDouble())));
//
//				// apply weights
//				final ImageProcessorWithMasks impFull = VisualizeTools.getImage(minimalTileSpec, 1.0, cacheOnDisk);
//
//				FloatProcessor fp = impFull.ip.convertToFloatProcessor();
//				fp.setMinAndMax(0, 255);
//				NormalizeLocalContrast nlc = new NormalizeLocalContrast( fp );
//				nlc.run(0, impFull.getHeight(), 3.0f, true, true );
//
//				//if ( minimalTileSpec.getImageCol() == 2 || minimalTileSpec.getImageCol() == 3 )
//				{
//					//new ImagePlus( "", impFull.ip.duplicate() ).show();
//					//new ImagePlus( "", fp.duplicate() ).show();
//				}
//				//ImageJFunctions.show(out);
//
//
//				AffineTransform2D t = new AffineTransform2D();
//				t.scale( 1.0/scale, 1.0/scale);
//
//				RealRandomAccessible scaled = RealViews.affine( Views.interpolate( Views.extendMirrorSingle( out ) , new NLinearInterpolatorFactory() ), t );
//				RealRandomAccess rs = scaled.realRandomAccess();
//
//				for ( int x = 0; x < impFull.ip.getWidth(); ++x )
//				{
//					rs.setPosition( x, 0 );
//					for ( int y = 0; y < impFull.ip.getHeight(); ++y )
//					{
//						rs.setPosition( y, 1 );
//						final double n = fp.getf(x, y);
//						final double i = impFull.ip.getf( x, y );
//						final double a = ((RealType)rs.get()).getRealDouble();
//						final double alpha = Math.min( 1, Math.max( 0, ( ( a - 1.01 ) / 0.05 ) ) );
//
//						if ( isSec26 && minimalTileSpec.getZ() >= 27759 && minimalTileSpec.getZ() >= 28016 && minimalTileSpec.getImageCol() == 2 )
//							fp.setf(x, y, (float)n );
//						else
//							fp.setf(x, y, (float)( (1.0 - alpha ) * i + alpha * n ) );
//					}
//				}
//
//				corrected.add( new ValuePair( (ByteProcessor)impFull.mask, fp ) );
//				adjustments.put(k, new double[] { 0,1,0 } );
//				//new ImagePlus( "", fp ).show();
//				//SimpleMultiThreading.threadHaltUnClean();
//			}
//			else
//			{
//				final ImageProcessorWithMasks impFull = VisualizeTools.getImage(minimalTileSpec, 1.0, cacheOnDisk);
//
//				FloatProcessor fp = impFull.ip.convertToFloatProcessor();
//				fp.setMinAndMax(0, 255);
//				NormalizeLocalContrast nlc = new NormalizeLocalContrast( fp );
//				nlc.run(0, impFull.getHeight(), 3.0f, true, true );
//
//				corrected.add( new ValuePair( (ByteProcessor)impFull.mask, fp ) );
//				adjustments.put(k, new double[] { 0,1,0 } );
//			}
//		}
//
//		//ImageJFunctions.show( fuse2d(interval, data, corrected, adjustments) );
//		//SimpleMultiThreading.threadHaltUnClean();
//
//		return fuse2d(interval, data, corrected, adjustments);
//	}
	
//	public static RandomAccessibleInterval<UnsignedByteType> renderIntensityAdjustedSlice(final String stack,
//																						  final RenderDataClient renderDataClient,
//																						  final Interval interval,
//																						  final double scale,
//																						  final boolean cacheOnDisk,
//																						  final int z)
//			throws IOException {
//
//		final List<MinimalTileSpecWrapper> data = getData(z, renderDataClient, stack);
//		final List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();
//
//		for ( final Pair<AffineModel2D,MinimalTileSpec> tile : data )
//		{
//			final MinimalTileSpec minimalTileSpec = tile.getB();
//
//			LOG.debug("renderIntensityAdjustedSlice: processing tile {} in column {}",
//					  minimalTileSpec.getTileId(), minimalTileSpec.getImageCol());
//
//			final ImageProcessorWithMasks imp = VisualizeTools.getImage(minimalTileSpec, scale, cacheOnDisk);
//			corrected.add( new ValuePair<>( (ByteProcessor)imp.mask, correct(imp, false) ) );
//
//			//new ImagePlus( "i", imp.ip ).show();
//			//new ImagePlus( "m", imp.mask ).show();
//			//new ImagePlus( "c", corrected.get( corrected.size() - 1).getB() ).show();
//			//SimpleMultiThreading.threadHaltUnClean();
//		}
//
//		//SimpleMultiThreading.threadHaltUnClean();
//
//		// order maps to [sub, mul, add]
//		final HashMap< Integer, double[] > adjustments = computeAdjustments( data, corrected );
//
//		return fuse2d(interval, data, corrected, adjustments);
//	}

	public static void main( String[] args ) throws IOException, InterruptedException, ExecutionException
	{
		String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		String owner = "Z0720_07m_BR"; //"flyem";
		String project = "Sec39";//"Sec26"; //"Z0419_25_Alpha3";
		String stack = "v1_acquire_trimmed_sp1";//"v2_acquire_trimmed_align"; //"v1_acquire_sp_nodyn_v2";

		final RenderDataClient renderDataClient = new RenderDataClient(baseUrl, owner, project );
		final StackMetaData meta =  renderDataClient.getStackMetaData( stack );
		final Bounds stackBounds = meta.getStats().getStackBounds();
		final Interval interval = RenderTools.stackBounds( meta );

		final int minZ = 23850;//27759;//20000;
		final int maxZ = 23850;//27759;//20000;
		final double stackScale = 1.0; // only full res supported right now
		final boolean cacheOnDisk = true;

		new ImageJ();

		final ImageStack stack3d = new ImageStack( (int)interval.dimension( 0 ), (int)interval.dimension( 1 ) );

		// make cache large enough to hold shared mask processors
		final ImageProcessorCache imageProcessorCache =
				new ImageProcessorCache(15_000L * 15_000L,
										false,
										false);

		for ( int z = minZ; z <= maxZ; ++z )
		{
			final String parametersUrl =
					renderDataClient.getRenderParametersUrlString(stack,
															stackBounds.getMinX(),
															stackBounds.getMinY(),
															z,
															(int) (stackBounds.getDeltaX() + 0.5),
															(int) (stackBounds.getDeltaY() + 0.5),
															1.0,
															null);

			final RenderParameters sliceRenderParameters = RenderParameters.loadFromUrl(parametersUrl);

			final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, (double) z);
			resolvedTiles.resolveTileSpecs();

			final ImageProcessorWithMasks slice =
					//renderIntensityAdjustedSlice(stack, renderDataClient, interval, scale, cacheOnDisk, z);
					//renderIntensityAdjustedSliceGauss(stack, renderDataClient, interval, false, cacheOnDisk, z);
					renderIntensityAdjustedSliceGlobalPerSlice(resolvedTiles,
															   sliceRenderParameters,
															   0.1,
															   null,
															   imageProcessorCache,
															   z,
															   DEFAULT_NUM_COEFFICIENTS,
															   new AffineIntensityCorrectionStrategy(),
															   1);
			stack3d.addSlice( slice.ip );
		}

		final ImagePlus imp1 = new ImagePlus( project + "_" + stack, stack3d );

		Calibration cal = new Calibration();
		cal.xOrigin = -(int)interval.min(0);
		cal.yOrigin = -(int)interval.min(1);
		cal.zOrigin = -minZ;
		cal.pixelWidth = 1.0/stackScale;
		cal.pixelHeight = 1.0/stackScale;
		cal.pixelDepth = 1.0;
		imp1.setCalibration( cal );

		imp1.show();
	}

	public static int DEFAULT_NUM_COEFFICIENTS = 8;

	private static final Logger LOG = LoggerFactory.getLogger(AdjustBlock.class);

}
