package org.janelia.render.client.solver;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.VolatileViews;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class VisualizeTools
{
	public static int[] cellDim = new int[]{ 10, 10, 10 };
	public static int maxCacheSize = Integer.MAX_VALUE;

	public static Pair< HashMap<String, AffineModel2D>, HashMap<String, MinimalTileSpec> > visualizeInfo(
			final Collection< ? extends SolveItemData< ?, ?, ? > > solveItems )
	{
		final HashMap<String, AffineModel2D> idToModels = new HashMap<>();
		final HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<>();

		for ( final SolveItemData< ?, ?, ? > solveItem : solveItems )
		{
			idToTileSpec.putAll( solveItem.idToTileSpec() );
			idToModels.putAll( solveItem.idToNewModel() );
		}

		return new ValuePair<>( idToModels, idToTileSpec );
	}

	public static Pair< HashMap<String, AffineModel2D>, HashMap<String, MinimalTileSpec> > visualizeInfo(
			final SolveItemData< ?, ?, ? > solveItem )
	{
		final HashMap<String, AffineModel2D> idToModels = new HashMap<>();
		final HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<>();

		idToTileSpec.putAll( solveItem.idToTileSpec() );
		idToModels.putAll( solveItem.idToNewModel() );

		return new ValuePair<>( idToModels, idToTileSpec );
	}

	public static BdvStackSource< ? > visualize( final Pair< HashMap<String, AffineModel2D>, HashMap<String, MinimalTileSpec> > data )
	{
		return visualize( data.getA(), data.getB(), constantIdToValue( data.getA().keySet() ) );
	}

	public static BdvStackSource< ? > visualizeMultiRes( final Pair< HashMap<String, AffineModel2D>, HashMap<String, MinimalTileSpec> > data )
	{
		return visualizeMultiRes( data.getA(), data.getB(), constantIdToValue( data.getA().keySet() ) );
	}

	public static BdvStackSource< ? > visualize(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final HashMap<String, Float> idToValue )
	{
		return visualize(idToModels, idToTileSpec, idToValue, new double[] { 1, 1, 1 }, Runtime.getRuntime().availableProcessors() );
	}

	public static BdvStackSource< ? > visualize(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final HashMap<String, Float> idToValue,
			final double[] scale,
			final int numThreads )
	{
		final RandomAccessibleInterval< FloatType > vis =
				new VisualizingRandomAccessibleInterval( idToModels, idToTileSpec, idToValue, scale );

		final RandomAccessibleInterval< FloatType > cachedImg = cacheRandomAccessibleInterval(
				vis,
				Integer.MAX_VALUE,
				new FloatType(),
				cellDim );

		final RandomAccessibleInterval< VolatileFloatType > volatileImg = VolatileViews.wrapAsVolatile( cachedImg );
	
		BdvOptions options = Bdv.options().numSourceGroups( 1 ).frameTitle( "Preview" ).numRenderingThreads( numThreads );
		BdvStackSource< ? > preview = BdvFunctions.show( volatileImg, "weights", options );
		preview.setDisplayRange( 0, 3 );

		return preview;
	}

	public static final HashMap<String, Float> constantIdToValue( final Collection< String > tileIds )
	{
		final HashMap<String, Float> idToValue = new HashMap<>();

		for ( final String tileId : tileIds )
			idToValue.put( tileId, 1.0f );

		return idToValue;
	}

	public static BdvStackSource< ? > visualizeMultiRes(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final HashMap<String, Float> idToValue )
	{
		return visualizeMultiRes( idToModels, idToTileSpec, idToValue, 1, 128, 2, Runtime.getRuntime().availableProcessors() );
	}

	public static BdvStackSource< ? > renderBDV( final ImagePlus imp, final double scale )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.set( 1 / scale, 0, 0 );
		t.set( 1 / scale, 1, 1 );
		t.set( 1.0, 2, 2 );

		BdvOptions options = Bdv.options().numSourceGroups( 1 ).frameTitle( "Preview" ).numRenderingThreads( Runtime.getRuntime().availableProcessors() );
		options.sourceTransform( t );

		final RandomAccessibleInterval cachedImg;
		BdvStackSource< ? > preview;

		if ( imp.getBitDepth() == 8  )
		{
			cachedImg = cacheRandomAccessibleInterval(
					ImageJFunctions.wrap( imp ),
					Integer.MAX_VALUE,
					new UnsignedByteType(),
					cellDim );
		}
		else if ( imp.getBitDepth() == 16 )
		{
			cachedImg = cacheRandomAccessibleInterval(
					ImageJFunctions.wrap( imp ),
					Integer.MAX_VALUE,
					new UnsignedShortType(),
					cellDim );	
		}
		else if ( imp.getBitDepth() == 32 )
		{
			cachedImg = cacheRandomAccessibleInterval(
					ImageJFunctions.wrap( imp ),
					Integer.MAX_VALUE,
					new FloatType(),
					cellDim );
		}
		else
		{
			LOG.info( "Cannot display ImagePlus in BDV, unknown format: " + imp.getBitDepth() );
			return null;
		}

		preview = BdvFunctions.show( VolatileViews.wrapAsVolatile( cachedImg ), "weights", options );
		preview.setDisplayRange( 0, 255 );

		return preview;
	}

	public static BdvStackSource< ? > visualizeMultiRes(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final int minDS, final int maxDS, final int dsInc,
			final int numThreads )
	{
		return visualizeMultiRes(idToModels, idToTileSpec, constantIdToValue( idToModels.keySet() ), minDS, maxDS, dsInc, numThreads);
	}

	public static BdvStackSource< ? > visualizeMultiRes(
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final HashMap<String, Float> idToValue,
			final int minDS, final int maxDS, final int dsInc,
			final int numThreads )
	{
		return visualizeMultiRes( null, idToModels, idToTileSpec, idToValue, minDS, maxDS, dsInc, numThreads );
	}

	public static BdvStackSource< ? > visualizeMultiRes(
			BdvStackSource< ? > source,
			final HashMap<String, AffineModel2D> idToModels,
			final HashMap<String, MinimalTileSpec> idToTileSpec,
			final HashMap<String, Float> idToValue,
			final int minDS, final int maxDS, final int dsInc,
			final int numThreads )
	{
		final ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = new ArrayList<>();

		for ( int downsampling = minDS; downsampling <= maxDS; downsampling *= dsInc )
		{
			LOG.info( "Assembling Multiresolution pyramid for downsampling=" + downsampling );

			// the virtual image is zeroMin, this transformation puts it into the global coordinate system
			final AffineTransform3D t = new AffineTransform3D();
			t.scale( downsampling );

			final RandomAccessibleInterval< FloatType > ra = 
					new VisualizingRandomAccessibleInterval( idToModels, idToTileSpec, idToValue, new double[] { 1.0/downsampling, 1.0/downsampling, 1.0/downsampling } );
			
			multiRes.add( new ValuePair<>( ra, t )  );
		}

		BdvStackSource< ? > source1;

		if ( source == null )
		{
			BdvOptions options = Bdv.options().numSourceGroups( 1 ).frameTitle( "Preview" ).numRenderingThreads( numThreads );
			source1 = BdvFunctions.show( new MultiResolutionSource( createVolatileRAIs( multiRes ), "preview" ), options );
		}
		else
		{
			source1 = BdvFunctions.show( new MultiResolutionSource( createVolatileRAIs( multiRes ), "preview" ), Bdv.options().addTo( source ).numRenderingThreads( numThreads ) );
		}

		source1.setDisplayRange( 0, 3 );

		return source1;
	}

	public static ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > createVolatileRAIs(
			final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes )
	{
		return createVolatileRAIs( multiRes, maxCacheSize, cellDim );
	}

	public static ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > createVolatileRAIs(
			final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes,
			final long maxCacheSize,
			final int[] cellDim )
	{
		final ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > volatileMultiRes = new ArrayList<>();

		for ( final Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > virtualImg : multiRes )
		{
			final RandomAccessibleInterval< FloatType > cachedImg = cacheRandomAccessibleInterval(
					virtualImg.getA(),
					maxCacheSize,
					new FloatType(),
					cellDim );

			final RandomAccessibleInterval< VolatileFloatType > volatileImg = VolatileViews.wrapAsVolatile( cachedImg );
			//DisplayImage.getImagePlusInstance( virtual, true, "ds="+ds, 0, 255 ).show();
			//ImageJFunctions.show( virtualVolatile );

			volatileMultiRes.add( new ValuePair<>( volatileImg, virtualImg.getB() ) );
		}

		return volatileMultiRes;
	}

	public static < T extends NativeType< T > > RandomAccessibleInterval< T > cacheRandomAccessibleInterval(
			final RandomAccessibleInterval< T > input,
			final long maxCacheSize,
			final T type,
			final int... cellDim )
	{
		final RandomAccessibleInterval< T > in;

		if ( Views.isZeroMin( input ) )
			in = input;
		else
			in = Views.zeroMin( input );
		
		final ReadOnlyCachedCellImgOptions options = new ReadOnlyCachedCellImgOptions().cellDimensions( cellDim ).maxCacheSize( maxCacheSize );
		final ReadOnlyCachedCellImgFactory factory = new ReadOnlyCachedCellImgFactory( options );

		final CellLoader< T > loader = new CellLoader< T >()
		{
			@Override
			public void load( final SingleCellArrayImg< T, ? > cell ) throws Exception
			{
				final Cursor< T > cursor = cell.localizingCursor();
				final RandomAccess< T > ra = in.randomAccess();
				
				while( cursor.hasNext() )
				{
					cursor.fwd();
					ra.setPosition( cursor );
					cursor.get().set( ra.get() );
				}
			}
		};

		final long[] dim = new long[ in.numDimensions() ];
		in.dimensions( dim );

		return translateIfNecessary( input, factory.create( dim, type, loader ) );
	}

	public static < T > RandomAccessibleInterval< T > translateIfNecessary( final Interval original, final RandomAccessibleInterval< T > copy )
	{
		if ( Views.isZeroMin( original ) )
		{
			return copy;
		}
		else
		{
			final long[] min = new long[ original.numDimensions() ];
			original.min( min );

			return Views.translate( copy, min );
		}
	}

	public static ImagePlus render( final HashMap<String, AffineModel2D> idToModels, final HashMap<String, MinimalTileSpec> idToTileSpec, final double scale ) throws NoninvertibleModelException
	{
		return render(idToModels, idToTileSpec, scale, Integer.MIN_VALUE, Integer.MAX_VALUE );
	}

	public static ImagePlus render( final HashMap<String, AffineModel2D> idToModelsIn, final HashMap<String, MinimalTileSpec> idToTileSpecIn, final double scale, final int minZ, final int maxZ ) throws NoninvertibleModelException
	{
		final HashMap<String, AffineModel2D> idToModels = new HashMap<String, AffineModel2D>();
		final HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<String, MinimalTileSpec>();

		int count = 0;

		LOG.info( "MinZ=" + minZ + " maxZ=" + maxZ );

		for ( final String tileId : idToModelsIn.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpecIn.get( tileId );
			final AffineModel2D model = idToModelsIn.get( tileId );
			
			if ( tileSpec.getZ() >= minZ && tileSpec.getZ() <= maxZ )
			{
				idToModels.put( tileId, model );
				idToTileSpec.put( tileId, tileSpec );
				++count;
			}
		}

		if ( count == 0 )
		{
			LOG.info( "No tiles for the range available." );
			return null;
		}
		else
		{
			LOG.info( "Rendering " + count + " tiles." );			
		}

		final double[] min = new double[] { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = new double[] { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

		final double[] tmpMin = new double[ 2 ];
		final double[] tmpMax = new double[ 2 ];

		final AffineModel2D scaleModel = new AffineModel2D();
		scaleModel.set( scale, 0, 0, scale, 0, 0 );

		final HashMap<String, AffineModel2D> idToRenderModels = new HashMap<>();

		// get bounding box
		for ( final String tileId : idToModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			min[ 2 ] = Math.min( min[ 2 ], tileSpec.getZ() );
			max[ 2 ] = Math.max( max[ 2 ], tileSpec.getZ() );

			final int w = tileSpec.getWidth();
			final int h = tileSpec.getHeight();

			final AffineModel2D model = idToModels.get( tileId ).copy();

			// scale the actual transform down to the scale level we want to render in
			model.preConcatenate( scaleModel );

			idToRenderModels.put( tileId, model );

			tmpMin[ 0 ] = 0;
			tmpMin[ 1 ] = 0;
			tmpMax[ 0 ] = w - 1;
			tmpMax[ 1 ] = h - 1;

			model.estimateBounds( tmpMin, tmpMax );

			min[ 0 ] = Math.min( min[ 0 ], Math.min( tmpMin[ 0 ], tmpMax[ 0 ] ) );
			max[ 0 ] = Math.max( max[ 0 ], Math.max( tmpMin[ 0 ], tmpMax[ 0 ] ) );

			min[ 1 ] = Math.min( min[ 1 ], Math.min( tmpMin[ 1 ], tmpMax[ 1 ] ) );
			max[ 1 ] = Math.max( max[ 1 ], Math.max( tmpMin[ 1 ], tmpMax[ 1 ] ) );
		}

		System.out.println( "x: " + min[ 0 ] + " >>> " + max[ 0 ] );
		System.out.println( "y: " + min[ 1 ] + " >>> " + max[ 1 ] );
		System.out.println( "z: " + min[ 2 ] + " >>> " + max[ 2 ] );

		final long[] minI = new long[ 3 ];
		final long[] maxI = new long[ 3 ];
		final long[] dimI = new long[ 3 ];

		for ( int d = 0; d < minI.length; ++d )
		{
			minI[ d ] = Math.round( Math.floor( min[ d ] ) );
			maxI[ d ] = Math.round( Math.ceil( max[ d ] ) );
			dimI[ d ] = maxI[ d ] - minI[ d ] + 1;
		}

		System.out.println( "BB x: " + minI[ 0 ] + " >>> " + maxI[ 0 ] + ", d=" + dimI[ 0 ] );
		System.out.println( "BB y: " + minI[ 1 ] + " >>> " + maxI[ 1 ] + ", d=" + dimI[ 1 ]);
		System.out.println( "BB z: " + minI[ 2 ] + " >>> " + maxI[ 2 ] + ", d=" + dimI[ 2 ]);

		// init image
		final ImagePlusImg< UnsignedByteType, ? > stack = new ImagePlusImgFactory<UnsignedByteType>( new UnsignedByteType()).create( dimI );
		final RandomAccessibleInterval< UnsignedByteType > img = Views.translate( stack, minI );

		final AffineModel2D invScaleModel = new AffineModel2D();
		invScaleModel.set( 1.0/scale, 0, 0, 1.0/scale, 0, 0 );

		// build the lookup z to tilespec for parallel rendering
		final HashMap<Integer, ArrayList< Pair<String,MinimalTileSpec> > > zToTileSpec = new HashMap<>(); 

		for ( final String tileId : idToRenderModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			final int z = (int)Math.round( tileSpec.getZ() );
			zToTileSpec.putIfAbsent(z, new ArrayList<>());
			zToTileSpec.get( z ).add( new ValuePair<>( tileId, tileSpec ) );
		}

		final AtomicInteger ai = new AtomicInteger();
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
		final ArrayList< Callable< Void > > tasks = new ArrayList<>();

		for ( final int z : zToTileSpec.keySet() )
		{
			final ArrayList< Pair<String,MinimalTileSpec> > data = zToTileSpec.get( z );

			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					for ( final Pair<String,MinimalTileSpec> pair : data )
					{
						final MinimalTileSpec tileSpec = pair.getB();
						final AffineModel2D model = idToRenderModels.get( pair.getA() );

						// scale the transform so it takes into account that the input images are scaled
						model.concatenate( invScaleModel );

						final ImageProcessorWithMasks imp = getImage( tileSpec, scale );
						RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.ip) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
						RealRandomAccessible<UnsignedByteType> interpolantMask = Views.interpolate( Views.extendZero( (RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.mask) ) ), new NearestNeighborInterpolatorFactory() );
						
						// draw
						final IterableInterval< UnsignedByteType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
						final Cursor< UnsignedByteType > c = slice.cursor();
						
						AffineTransform2D affine = new AffineTransform2D();
						double[] array = new double[6];
						model.toArray( array );
						affine.set( array[0], array[2], array[4], array[1], array[3], array[5] );
						final Cursor< FloatType > cSrc = Views.interval( RealViews.affine( interpolant, affine ), img ).cursor();
						final Cursor< UnsignedByteType > cMask = Views.interval( RealViews.affine( interpolantMask, affine ), img ).cursor();
						
						while ( c.hasNext() )
						{
							c.fwd();
							cMask.fwd();
							cSrc.fwd();
							if (cMask.get().get() == 255) {
								FloatType srcType = cSrc.get();
								float value = srcType.get();
								if (value >= 0) {
									UnsignedByteType type = c.get();
									final float currentValue = type.get();
									if ( currentValue > 0 )
										type.setReal( ( value + currentValue ) / 2 );
									else
										type.setReal( value );
								}
							}
						}

						IJ.showProgress( ai.getAndIncrement(), idToRenderModels.keySet().size() - 1 );
					}
					return null;
				}
			});
		}

		try
		{
			taskExecutor.invokeAll( tasks );
		}
		catch (InterruptedException e)
		{
			e.printStackTrace();
		}

		taskExecutor.shutdown();
		
		IJ.showProgress( 0.0 );
		
		/*
		// render the images single-threaded
		int i = 0;
		for ( final String tileId : idToRenderModels.keySet() )
		{
			final MinimalTileSpec tileSpec = idToTileSpec.get( tileId );
			final long z = Math.round( tileSpec.getZ() );
			final AffineModel2D model = idToRenderModels.get( tileId );

			// scale the transform so it takes into account that the input images are scaled
			model.concatenate( invScaleModel );

			final ImageProcessorWithMasks imp = getImage( tileSpec, scale );
			RealRandomAccessible<FloatType> interpolant = Views.interpolate( Views.extendValue( (RandomAccessibleInterval<FloatType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.ip) ), new FloatType(-1f) ), new NLinearInterpolatorFactory<>() );
			RealRandomAccessible<UnsignedByteType> interpolantMask = Views.interpolate( Views.extendZero( (RandomAccessibleInterval<UnsignedByteType>)(Object)ImagePlusImgs.from( new ImagePlus("", imp.mask) ) ), new NearestNeighborInterpolatorFactory() );
			
			// draw
			final IterableInterval< UnsignedByteType > slice = Views.iterable( Views.hyperSlice( img, 2, z ) );
			final Cursor< UnsignedByteType > c = slice.cursor();
			
			AffineTransform2D affine = new AffineTransform2D();
			double[] array = new double[6];
			model.toArray( array );
			affine.set( array[0], array[2], array[4], array[1], array[3], array[5] );
			final Cursor< FloatType > cSrc = Views.interval( RealViews.affine( interpolant, affine ), img ).cursor();
			final Cursor< UnsignedByteType > cMask = Views.interval( RealViews.affine( interpolantMask, affine ), img ).cursor();
			
			while ( c.hasNext() )
			{
				c.fwd();
				cMask.fwd();
				cSrc.fwd();
				if (cMask.get().get() == 255) {
					FloatType srcType = cSrc.get();
					float value = srcType.get();
					if (value >= 0) {
						UnsignedByteType type = c.get();
						final float currentValue = type.get();
						if ( currentValue > 0 )
							type.setReal( ( value + currentValue ) / 2 );
						else
							type.setReal( value );
					}
				}
			}

			IJ.showProgress( ++i, idToRenderModels.keySet().size() - 1 );
		}
		*/

		//final ImagePlus imp = ImageJFunctions.wrap( img, "stack", null );
		final ImagePlus imp = stack.getImagePlus();

		Calibration cal = new Calibration();
		cal.xOrigin = -minI[ 0 ];
		cal.yOrigin = -minI[ 1 ];
		cal.zOrigin = -minI[ 2 ];
		cal.pixelWidth = 1.0/scale;
		cal.pixelHeight = 1.0/scale;
		cal.pixelDepth = 1.0;
		imp.setCalibration( cal );
		imp.setDimensions( 1, (int)dimI[ 2 ], 1 );
		imp.setDisplayRange( 0, 255 );
		imp.show();

		return imp;
	}

	protected static FloatProcessor getFullResImage( final MinimalTileSpec tileSpec )
	{
		final String fileName = tileSpec.getFileName();

		if ( new File( fileName ).exists() )
			return new ImagePlus( fileName ).getProcessor().convertToFloatProcessor();
		else
		{
			System.out.println( "WARNING: File path '" + new File( fileName ).getAbsolutePath() + "' does not exist. Shares mounted?" );
			return null;
		}

	}

	protected static ImageProcessor getFullResMask( final MinimalTileSpec tileSpec )
	{
		final String fileNameMask = tileSpec.getMaskFileName();

		if ( new File( fileNameMask ).exists() )
			return new ImagePlus( fileNameMask ).getProcessor();
		else
		{
			System.out.println( "WARNING: File path '" + new File( fileNameMask ).getAbsolutePath() + "' does not exist. Shares mounted?" );
			return null;
		}
	}

	protected static ImageProcessorWithMasks getImage( final MinimalTileSpec tileSpec, final double scale )
	{
		// old code:
		final File imageFile = new File( "tmp", tileSpec.getTileId() + "_" + scale + ".image.tif" );
		final File maskFile = new File( "tmp", tileSpec.getTileId() + "_" + scale + ".mask.tif" );

		final ImageProcessorWithMasks imp;

		if ( imageFile.exists() && maskFile.exists() )
		{
			//System.out.println( "Loading: " + imageFile );
			//System.out.println( "Loading: " + maskFile );

			final ImagePlus image = new ImagePlus( imageFile.getAbsolutePath() );
			final ImagePlus mask = new ImagePlus( maskFile.getAbsolutePath() );

			imp = new ImageProcessorWithMasks( image.getProcessor(), mask.getProcessor(), null );
		}
		else
		{
			// this gives a transformed image, but we need a raw image
			/*
			// Load the image, this is not efficient!
			final RenderParameters params = getRenderParametersForTile(
					parameters.renderWeb.owner,
					parameters.renderWeb.project,
					parameters.stack,
					tileSpec.getTileId(),
					scale );
	
			imp = Renderer.renderImageProcessorWithMasks(params, ImageProcessorCache.DISABLED_CACHE);
			*/
			
			// this gives a raw image
			FloatProcessor imageFP = getFullResImage( tileSpec );
			ImageProcessor maskIP = getFullResMask( tileSpec );

			imageFP = (FloatProcessor)imageFP.resize( (int)Math.round( imageFP.getWidth() * scale ), (int)Math.round( imageFP.getHeight() * scale ), true );
			maskIP = maskIP.resize( (int)Math.round( maskIP.getWidth() * scale ), (int)Math.round( maskIP.getHeight() * scale ), true );

			// hack to get a not transformed image:
			imp = new ImageProcessorWithMasks( imageFP, maskIP, null );

			// write temp if doesn't exist
			if ( !imageFile.exists() || !maskFile.exists() )
			{
				System.out.println( "Saving: " + imageFile );
				System.out.println( "Saving: " + maskFile );
	
				final ImagePlus image = new ImagePlus( "image", imp.ip );
				final ImagePlus mask = new ImagePlus( "mask", imp.mask );
	
				new FileSaver( image ).saveAsTiff( imageFile.getAbsolutePath() );
				new FileSaver( mask ).saveAsTiff( maskFile.getAbsolutePath() );
			}
		}

		return imp;
	}

	private static final Logger LOG = LoggerFactory.getLogger(VisualizeTools.class);
}
