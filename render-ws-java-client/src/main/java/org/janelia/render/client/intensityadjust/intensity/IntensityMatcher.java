package org.janelia.render.client.intensityadjust.intensity;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.solver.visualize.VisualizeTools;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import mpicbg.models.Affine1D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel1D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.FloatImagePlus;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class IntensityMatcher
{
	final private class Matcher implements Runnable
	{
		//final private Rectangle roi;
		final private ValuePair< MinimalTileSpecWrapper, MinimalTileSpecWrapper > patchPair;
		final private HashMap< MinimalTileSpecWrapper, ArrayList< Tile< ? > > > coefficientsTiles;
		final private PointMatchFilter filter;
		final private double scale;
		final private int numCoefficients;
		final int meshResolution;
		final ImageProcessorCache imageProcessorCache;

		public Matcher(
				final ValuePair< MinimalTileSpecWrapper, MinimalTileSpecWrapper > patchPair,
				final HashMap< MinimalTileSpecWrapper, ArrayList< Tile< ? > > > coefficientsTiles,
				final PointMatchFilter filter,
				final double scale,
				final int numCoefficients,
				final int meshResolution,
				final ImageProcessorCache imageProcessorCache)
		{
			//this.roi = roi;
			this.patchPair = patchPair;
			this.coefficientsTiles = coefficientsTiles;
			this.filter = filter;
			this.scale = scale;
			this.numCoefficients = numCoefficients;
			this.meshResolution = meshResolution;
			this.imageProcessorCache = imageProcessorCache;
		}

		@Override
		public void run()
		{
			final MinimalTileSpecWrapper p1 = patchPair.getA();
			final MinimalTileSpecWrapper p2 = patchPair.getB();

			final Interval i1 = Intervals.smallestContainingInterval( getBoundingBox( p1 ) );
			final Rectangle box1 = new Rectangle( (int)i1.min( 0 ), (int)i1.min( 1 ), (int)i1.dimension( 0 ), (int)i1.dimension( 1 ));// p1.getBoundingBox().intersection( roi );

			/* get the coefficient tiles */
			final ArrayList< Tile< ? > > p1CoefficientsTiles = coefficientsTiles.get( p1 );

			/* render intersection */
			final Interval i2 = Intervals.smallestContainingInterval( getBoundingBox( p2 ) );
			final Rectangle box2 = new Rectangle( (int)i2.min( 0 ), (int)i2.min( 1 ), (int)i2.dimension( 0 ), (int)i2.dimension( 1 ));//p2.getBoundingBox();
			final Rectangle box = box1.intersection( box2 );

			final int w = ( int ) ( box.width * scale + 0.5 );
			final int h = ( int ) ( box.height * scale + 0.5 );
			final int n = w * h;

			final FloatProcessor pixels1 = new FloatProcessor( w, h );
			final FloatProcessor weights1 = new FloatProcessor( w, h );
			final ColorProcessor coefficients1 = new ColorProcessor( w, h );
			final FloatProcessor pixels2 = new FloatProcessor( w, h );
			final FloatProcessor weights2 = new FloatProcessor( w, h );
			final ColorProcessor coefficients2 = new ColorProcessor( w, h );

			Render.render(p1, numCoefficients, numCoefficients, pixels1, weights1, coefficients1, box.x, box.y, scale, meshResolution, imageProcessorCache);
			Render.render(p2, numCoefficients, numCoefficients, pixels2, weights2, coefficients2, box.x, box.y, scale, meshResolution, imageProcessorCache);

			//new ImagePlus( "pixels1", pixels1 ).show();
			//new ImagePlus( "weights1", weights1 ).show();
			//new ImagePlus( "pixels2", pixels2 ).show();
			//new ImagePlus( "weights2", weights2 ).show();
			//SimpleMultiThreading.threadHaltUnClean();

			/*
			 * generate a matrix of all coefficients in p1 to all
			 * coefficients in p2 to store matches
			 */
			final ArrayList< ArrayList< PointMatch > > list = new ArrayList< ArrayList< PointMatch > >();
			for ( int i = 0; i < numCoefficients * numCoefficients * numCoefficients * numCoefficients; ++i )
				list.add( new ArrayList< PointMatch >() );
			final ListImg< ArrayList< PointMatch > > matrix = new ListImg< ArrayList< PointMatch > >( list, numCoefficients * numCoefficients, numCoefficients * numCoefficients );
			final ListRandomAccess< ArrayList< PointMatch > > ra = matrix.randomAccess();

			/*
			 * iterate over all pixels and feed matches into the match
			 * matrix
			 */
			for ( int i = 0; i < n; ++i )
			{
				final int c1 = coefficients1.get( i );
				if ( c1 > 0 )
				{
					final int c2 = coefficients2.get( i );
					if ( c2 > 0 )
					{
						final double w1 = weights1.getf( i );
						if ( w1 > 0 )
						{
							final double w2 = weights2.getf( i );
							if ( w2 > 0 )
							{
								final double p = pixels1.getf( i );
								final double q = pixels2.getf( i );
								final PointMatch pq = new PointMatch( new Point( new double[] { p } ), new Point( new double[] { q } ), w1 * w2 );

								/* first label is 1 */
								ra.setPosition( c1 - 1, 0 );
								ra.setPosition( c2 - 1, 1 );
								ra.get().add( pq );
							}
						}
					}
				}
			}

			/* filter matches */
			final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();
			for ( final ArrayList< PointMatch > candidates : matrix )
			{
				inliers.clear();
				filter.filter( candidates, inliers );
				candidates.clear();
				candidates.addAll( inliers );
			}

			/* get the coefficient tiles of p2 */
			final ArrayList< Tile< ? > > p2CoefficientsTiles = coefficientsTiles.get( p2 );

			/* connect tiles across patches */
			for ( int i = 0; i < numCoefficients * numCoefficients; ++i )
			{
				final Tile< ? > t1 = p1CoefficientsTiles.get( i );
				ra.setPosition( i, 0 );
				for ( int j = 0; j < numCoefficients * numCoefficients; ++j )
				{
					ra.setPosition( j, 1 );
					final ArrayList< PointMatch > matches = ra.get();
					if ( matches.size() > 0 )
					{
						final Tile< ? > t2 = p2CoefficientsTiles.get( j );
						//synchronized ( MatchIntensities.this )
						{
							t1.connect( t2, ra.get() );
							System.out.println( "Connected patch " + p1.getImageCol() + ", coefficient " + i + "  +  patch " + p2.getImageCol() + ", coefficient " + j + " by " + matches.size() + " samples." );
						}
					}
				}
			}
		}
	}

	public < M extends Model< M > & Affine1D< M > > ArrayList < OnTheFlyIntensity > match(
			final List<MinimalTileSpecWrapper> patches,
			final double scale,
			final int numCoefficients,
			final double lambda1,
			final double lambda2,
			final double neighborWeight,
			final int iterations,
			final ImageProcessorCache imageProcessorCache) throws InterruptedException, ExecutionException
	{
		final PointMatchFilter filter = new RansacRegressionReduceFilter();

		/* generate coefficient tiles for all patches
		 * TODO consider offering alternative models */
		final HashMap< MinimalTileSpecWrapper, ArrayList< Tile< ? extends M > > > coefficientsTiles =
				( HashMap ) generateCoefficientsTiles(
						patches,
						new InterpolatedAffineModel1D< InterpolatedAffineModel1D< AffineModel1D, TranslationModel1D >, IdentityModel >(
								new InterpolatedAffineModel1D< AffineModel1D, TranslationModel1D >(
										new AffineModel1D(), new TranslationModel1D(), lambda1 ),
								new IdentityModel(), lambda2 ),
						numCoefficients * numCoefficients );

		/* completed patches */
		final HashSet< MinimalTileSpecWrapper > completedPatches = new HashSet<>();

		/* collect patch pairs */
		System.out.println( "Collecting patch pairs ... " );
		final ArrayList< ValuePair< MinimalTileSpecWrapper, MinimalTileSpecWrapper > > patchPairs = new ArrayList<>();

		for ( final MinimalTileSpecWrapper p1 : patches )
		{
			completedPatches.add( p1 );

			final RealInterval r1 = getBoundingBox(p1);

			final ArrayList< MinimalTileSpecWrapper > p2s = new ArrayList<>();

			for ( final MinimalTileSpecWrapper p2 : patches )
			{
				final FinalRealInterval i = Intervals.intersect( r1, getBoundingBox(p2) );

				if ( i.realMax( 0 ) - i.realMin( 0 ) > 0 && i.realMax( 1 ) - i.realMin( 1 ) > 0 )
					p2s.add( p2 );
			}

			for ( final MinimalTileSpecWrapper p2 : p2s )
			{
				/*
				 * if this patch had been processed earlier, all matches are
				 * already in
				 */
				if ( completedPatches.contains( p2 ) )
					continue;

				patchPairs.add( new ValuePair<>( p1, p2 ) );
				System.out.println( p1.getImageCol() + " <> " + p2.getImageCol() );
			}
		}

		final int numThreads = 1;
		final int meshResolution = 64; //?

		System.out.println( "Matching intensities using " + numThreads + " threads ... " );

		final ExecutorService exec = Executors.newFixedThreadPool( numThreads );
		final ArrayList< Future< ? > > futures = new ArrayList<>();
		for ( final ValuePair< MinimalTileSpecWrapper, MinimalTileSpecWrapper > patchPair : patchPairs )
		{
			futures.add(
					exec.submit(
							new Matcher(
									patchPair,
									( HashMap )coefficientsTiles,
									filter,
									scale,
									numCoefficients,
									meshResolution,
									imageProcessorCache ) ) );
		}

		for ( final Future< ? > future : futures )
			future.get();

		/* connect tiles within patches */
		System.out.println( "Connecting coefficient tiles in the same patch  ... " );

		for ( final MinimalTileSpecWrapper p1 : completedPatches )
		{
			/* get the coefficient tiles */
			final ArrayList< Tile< ? extends M > > p1CoefficientsTiles = coefficientsTiles.get( p1 );

			for ( int y = 1; y < numCoefficients; ++y )
			{
				final int yr = numCoefficients * y;
				final int yr1 = yr - numCoefficients;
				for ( int x = 0; x < numCoefficients; ++x )
				{
					identityConnect( p1CoefficientsTiles.get( yr1 + x ), p1CoefficientsTiles.get( yr + x ), neighborWeight );
				}
			}
			for ( int y = 0; y < numCoefficients; ++y )
			{
				final int yr = numCoefficients * y;
				for ( int x = 1; x < numCoefficients; ++x )
				{
					final int yrx = yr + x;
					identityConnect( p1CoefficientsTiles.get( yrx ), p1CoefficientsTiles.get( yrx - 1 ), neighborWeight );
				}
			}
		}

		/* optimize */
		System.out.println( "Optimizing ... " );
		final TileConfiguration tc = new TileConfiguration();
		for ( final ArrayList< Tile< ? extends M > > coefficients : coefficientsTiles.values() )
		{
			// for ( final Tile< ? > t : coefficients )
			// if ( t.getMatches().size() == 0 )
			// IJ.log( "bang" );
			tc.addTiles( coefficients );
		}

		try
		{
			//final ErrorStatistic observer = new ErrorStatistic( iterations + 1 );
			//tc.optimizeSilentlyConcurrent( observer, 0.01f, iterations, iterations, 0.75f );
			
			TileUtil.optimizeConcurrently(new ErrorStatistic( iterations + 1 ), 0.01f, iterations, iterations, 0.75f,
					tc, tc.getTiles(), tc.getFixedTiles(), 1 );
			
			//tc.optimize( 0.01f, iterations, iterations, 0.75f );
		}
		catch ( final Exception e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// TODO: this code should be computed on-the-fly as a function of the coefficients
		final ArrayList < OnTheFlyIntensity > correctedOnTheFly = new ArrayList<>();

		for ( final MinimalTileSpecWrapper p : patches )
		{
			final OnTheFlyIntensity otfi = new OnTheFlyIntensity();

			otfi.numCoefficients = numCoefficients;
			otfi.p = p;

			/* save coefficients */
			otfi.ab_coefficients = new double[ numCoefficients * numCoefficients ][ 2 ];

			final ArrayList< Tile< ? extends M > > tiles = coefficientsTiles.get( p );

			for ( int i = 0; i < numCoefficients * numCoefficients; ++i )
			{
				final Tile< ? extends M > t = tiles.get( i );
				final Affine1D< ? > affine = t.getModel();
				affine.toArray( otfi.ab_coefficients[ i ] );
			}

			correctedOnTheFly.add( otfi );
		}

		return correctedOnTheFly;

		/*
		final double[] ab = new double[ 2 ];

		List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();

		// iterate in the same order as the input
		for ( final MinimalTileSpecWrapper p : patches )
		//for ( final Entry< Pair<AffineModel2D,MinimalTileSpec>, ArrayList< Tile< ? extends M > > > entry : coefficientsTiles.entrySet() )
		{
			//final Pair<AffineModel2D,MinimalTileSpec> p = entry.getKey();
			//final ArrayList< Tile< ? extends M > > tiles = entry.getValue();
			final ArrayList< Tile< ? extends M > > tiles = coefficientsTiles.get( p );

			final FloatProcessor as = new FloatProcessor( numCoefficients, numCoefficients );
			final FloatProcessor bs = new FloatProcessor( numCoefficients, numCoefficients );

			final ImageProcessorWithMasks imp = VisualizeTools.getUntransformedProcessorWithMasks(p.getTileSpec(),
																								  imageProcessorCache);

			FloatProcessor fp = imp.ip.convertToFloatProcessor();
			fp.resetMinAndMax();
			final double min = 0;//fp.getMin();//patch.getMin();
			final double max = 255;//fp.getMax();//patch.getMax();
			System.out.println( min + ", " + max );

			for ( int i = 0; i < numCoefficients * numCoefficients; ++i )
			{
				final Tile< ? extends M > t = tiles.get( i );
				final Affine1D< ? > affine = t.getModel();
				affine.toArray( ab );

				// coefficients mapping into existing [min, max] 
				as.setf( i, ( float ) ab[ 0 ] );
				bs.setf( i, ( float ) ( ( max - min ) * ab[ 1 ] + min - ab[ 0 ] * min ) );
			}
			final ImageStack coefficientsStack = new ImageStack( numCoefficients, numCoefficients );
			coefficientsStack.addSlice( as );
			coefficientsStack.addSlice( bs );

			//new ImagePlus( "a", as ).show();
			//new ImagePlus( "b", bs ).show();
			//SimpleMultiThreading.threadHaltUnClean();

			//final String itsPath = itsDir + FSLoader.createIdPath( Long.toString( p.getId() ), "it", ".tif" );
			//new File( itsPath ).getParentFile().mkdirs();
			//IJ.saveAs( new ImagePlus( "", coefficientsStack ), "tif", itsPath );

			@SuppressWarnings({"rawtypes"})
			final LinearIntensityMap<FloatType> map =
					new LinearIntensityMap<FloatType>(
							(FloatImagePlus)ImagePlusImgs.from( new ImagePlus( "", coefficientsStack ) ));


			final long[] dims = new long[]{imp.getWidth(), imp.getHeight()};
			final Img< FloatType > img = ArrayImgs.floats((float[])fp.getPixels(), dims);

			map.run(img);

			//new ImagePlus( "imp.ip", imp.ip ).show();
			//new ImagePlus( "fp", fp ).show();
			//SimpleMultiThreading.threadHaltUnClean();

			corrected.add( new ValuePair<>( (ByteProcessor)imp.mask, fp ) );
		}

		return corrected;
		*/
	}

	public static class OnTheFlyIntensityFloatProcessorSupplier extends OnTheFlyIntensitySupplier implements Supplier<FloatProcessor>
	{
		public OnTheFlyIntensityFloatProcessorSupplier( final OnTheFlyIntensity otfi, final ImageProcessorCache imageProcessorCache )
		{
			super(otfi, imageProcessorCache);
		}

		@Override
		public FloatProcessor get() { return otfi.computeIntensityCorrectionOnTheFly(imageProcessorCache); }
	}

	public static class OnTheFlyIntensityByteProcessorSupplier extends OnTheFlyIntensitySupplier implements Supplier<ByteProcessor>
	{
		public OnTheFlyIntensityByteProcessorSupplier( final OnTheFlyIntensity otfi, final ImageProcessorCache imageProcessorCache )
		{
			super(otfi, imageProcessorCache);
		}

		@Override
		public ByteProcessor get() { return otfi.computeIntensityCorrection8BitOnTheFly(imageProcessorCache); }
	}

	public static abstract class OnTheFlyIntensitySupplier
	{
		final OnTheFlyIntensity otfi;
		final ImageProcessorCache imageProcessorCache;

		public OnTheFlyIntensitySupplier( final OnTheFlyIntensity otfi, final ImageProcessorCache imageProcessorCache )
		{
			this.otfi = otfi;
			this.imageProcessorCache = imageProcessorCache;
		}
	}

	public static class OnTheFlyIntensity
	{
		MinimalTileSpecWrapper p;

		// all coefficients needed for a single image (depends how its broken up initially), each tile is a 1D affine, i.e. 2 numbers
		// ArrayList< Tile< ? extends Affine1D< ? > > > subRegionTiles;
		// we only store the actual coefficients
		// contains [numCoefficients * numCoefficients][ab]
		double[][] ab_coefficients;

		// e.g. if numCoefficients==4, then we have 16 tiles per image
		int numCoefficients;

		public FloatProcessor computeIntensityCorrectionOnTheFly( final ImageProcessorCache imageProcessorCache )
		{
			return IntensityMatcher.computeIntensityCorrectionOnTheFly( p, ab_coefficients, numCoefficients, imageProcessorCache);
		}

		public ByteProcessor computeIntensityCorrection8BitOnTheFly( final ImageProcessorCache imageProcessorCache )
		{
			final FloatProcessor correctedSource = computeIntensityCorrectionOnTheFly(imageProcessorCache);
			
			// Need to reset intensity range back to full 8-bit before converting to byte processor!
			correctedSource.setMinAndMax(0, 255);
			final ByteProcessor correctedSource8Bit = correctedSource.convertToByteProcessor();

			return correctedSource8Bit;
		}
	}

	public static /*Pair<ByteProcessor, */FloatProcessor/*>*/ computeIntensityCorrectionOnTheFly(
			final MinimalTileSpecWrapper p,
			final double[][] ab_coefficients, // all coefficients needed for a single image (depends how its broken up initially), each tile is a 1D affine, i.e. 2 numbers
			final int numCoefficients, // e.g. if numCoefficients==4, then we have 16 tiles per image
			final ImageProcessorCache imageProcessorCache )
	{
		//final ArrayList< Tile< ? extends M > > tiles = coefficientsTiles.get( p );

		final FloatProcessor as = new FloatProcessor( numCoefficients, numCoefficients );
		final FloatProcessor bs = new FloatProcessor( numCoefficients, numCoefficients );

		final ImageProcessorWithMasks imp = VisualizeTools.getUntransformedProcessorWithMasks(p.getTileSpec(),
																							  imageProcessorCache);

		FloatProcessor fp = imp.ip.convertToFloatProcessor();
		fp.resetMinAndMax();
		final double min = 0;//fp.getMin();//patch.getMin();
		final double max = 255;//fp.getMax();//patch.getMax();
		System.out.println( min + ", " + max );

		for ( int i = 0; i < numCoefficients * numCoefficients; ++i )
		{
			/*
			final Tile< ? extends Affine1D< ? > > t = tiles.get( i );
			final Affine1D< ? > affine = t.getModel();
			affine.toArray( ab );
			*/

			final double[] ab = ab_coefficients[ i ];

			/* coefficients mapping into existing [min, max] */
			as.setf( i, ( float ) ab[ 0 ] );
			bs.setf( i, ( float ) ( ( max - min ) * ab[ 1 ] + min - ab[ 0 ] * min ) );
		}
		final ImageStack coefficientsStack = new ImageStack( numCoefficients, numCoefficients );
		coefficientsStack.addSlice( as );
		coefficientsStack.addSlice( bs );

		//new ImagePlus( "a", as ).show();
		//new ImagePlus( "b", bs ).show();
		//SimpleMultiThreading.threadHaltUnClean();

		//final String itsPath = itsDir + FSLoader.createIdPath( Long.toString( p.getId() ), "it", ".tif" );
		//new File( itsPath ).getParentFile().mkdirs();
		//IJ.saveAs( new ImagePlus( "", coefficientsStack ), "tif", itsPath );

		@SuppressWarnings({"rawtypes"})
		final LinearIntensityMap<FloatType> map =
				new LinearIntensityMap<FloatType>(
						(FloatImagePlus)ImagePlusImgs.from( new ImagePlus( "", coefficientsStack ) ));

		final long[] dims = new long[]{imp.getWidth(), imp.getHeight()};
		final Img< FloatType > img = ArrayImgs.floats((float[])fp.getPixels(), dims);

		map.run(img);

		//new ImagePlus( "imp.ip", imp.ip ).show();
		//new ImagePlus( "fp", fp ).show();
		//SimpleMultiThreading.threadHaltUnClean();

		return fp;
	}
	
	final static protected void identityConnect( final Tile< ? > t1, final Tile< ? > t2, final double weight )
	{
		final ArrayList< PointMatch > matches = new ArrayList< PointMatch >();
		matches.add( new PointMatch( new Point( new double[] { 0 } ), new Point( new double[] { 0 } ) ) );
		matches.add( new PointMatch( new Point( new double[] { 1 } ), new Point( new double[] { 1 } ) ) );
		t1.connect( t2, matches );
	}

	final public static RealInterval getBoundingBox( final MinimalTileSpecWrapper m )
	{
		final double[] p1min = new double[]{ m.getTileSpec().getMinX(), m.getTileSpec().getMinY() };
		final double[] p1max = new double[]{ m.getTileSpec().getMaxX(), m.getTileSpec().getMaxY() };

		/*
		final double[] p1min = new double[]{ 0,0 };
		final double[] p1max = new double[]{ m.getWidth() - 1, m.getHeight() - 1 };
		t.estimateBounds( p1min, p1max );
		*/

		return new FinalRealInterval(p1min, p1max);
	}

	final static protected < T extends Model< T > & Affine1D< T > > HashMap< MinimalTileSpecWrapper, ArrayList< Tile< T > > > generateCoefficientsTiles(
			final Collection< MinimalTileSpecWrapper > patches,
			final T template,
			final int nCoefficients )
	{
		final HashMap< MinimalTileSpecWrapper, ArrayList< Tile< T > > > map = new HashMap<>();
		for ( final MinimalTileSpecWrapper p : patches )
		{
			final ArrayList< Tile< T > > coefficientModels = new ArrayList< Tile< T > >();
			for ( int i = 0; i < nCoefficients; ++i )
				coefficientModels.add( new Tile< T >( template.copy() ) );

			map.put( p, coefficientModels );
		}
		return map;
	}

}
