package org.janelia.render.client.intensityadjust.intensity;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;

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

import mpicbg.models.Affine1D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.IntensityCorrectionStrategy;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListRandomAccess;
import net.imglib2.util.Intervals;
import net.imglib2.util.StopWatch;
import net.imglib2.util.ValuePair;

public class IntensityMatcher
{

	static final private class Matcher implements Runnable
	{
		//final private Rectangle roi;
		final private ValuePair<TileSpec, TileSpec> patchPair;
		final private HashMap<TileSpec, ArrayList< Tile< ? > > > coefficientsTiles;
		final private PointMatchFilter filter;
		final private double scale;
		final private int numCoefficients;
		final int meshResolution;
		final ImageProcessorCache imageProcessorCache;

		public Matcher(
				final ValuePair<TileSpec, TileSpec> patchPair,
				final HashMap<TileSpec, ArrayList<Tile<?>>> coefficientsTiles,
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
			final TileSpec p1 = patchPair.getA();
			final TileSpec p2 = patchPair.getB();

			final StopWatch stopWatch = StopWatch.createAndStart();

			LOG.info("run: entry, pair {} <-> {}", p1.getTileId(), p2.getTileId());

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

			LOG.info("run: generate matrix for pair {} <-> {} and filter", p1.getTileId(), p2.getTileId());

			/*
			 * generate a matrix of all coefficients in p1 to all
			 * coefficients in p2 to store matches
			 */
			final ArrayList< ArrayList< PointMatch > > list = new ArrayList<>();
			final int dimSize = numCoefficients * numCoefficients;
			final int matrixSize = dimSize * dimSize;
			for (int i = 0; i < matrixSize; ++i) {
				list.add(new ArrayList<>());
			}

			final ListImg< ArrayList< PointMatch > > matrix = new ListImg<>(list, dimSize, dimSize);
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
			final ArrayList< PointMatch > inliers = new ArrayList<>();
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
			int connectionCount = 0;
			for ( int i = 0; i < dimSize; ++i )
			{
				final Tile< ? > t1 = p1CoefficientsTiles.get( i );
				ra.setPosition( i, 0 );
				for ( int j = 0; j < dimSize; ++j )
				{
					ra.setPosition( j, 1 );
					final ArrayList< PointMatch > matches = ra.get();
					if (!matches.isEmpty()) {
						final Tile< ? > t2 = p2CoefficientsTiles.get( j );
						//synchronized ( MatchIntensities.this )
						{
							t1.connect( t2, ra.get() );
//							LOG.info("run: connected pair {} [{}] <-> {} [{}] with {} samples",
//									 p1.getTileId(), i, p2.getTileId(), j, matches.size());
							connectionCount++;
						}
					}
				}
			}

			stopWatch.stop();

//			LOG.info("run: exit, pair {} <-> {} has {} connections, matching took {}, cacheStats are {}",
//					 p1.getTileId(), p2.getTileId(), connectionCount, stopWatch, imageProcessorCache.getStats());
			LOG.info("run: exit, pair {} <-> {} has {} connections, matching took {}",
					 p1.getTileId(), p2.getTileId(), connectionCount, stopWatch);
		}
	}

	public ArrayList<OnTheFlyIntensity> match(
			final List<TileSpec> patches,
			final double scale,
			final int numCoefficients,
			final IntensityCorrectionStrategy strategy,
			final Integer zDistance,
			final double neighborWeight,
			final int iterations,
			final ImageProcessorCache imageProcessorCache,
			final int numThreads) throws InterruptedException, ExecutionException
	{
		LOG.info("match: entry, collecting pairs for {} patches with zDistance {}",
				 patches.size(), zDistance);

		final PointMatchFilter filter = strategy.provideOutlierRemoval();

		// generate coefficient tiles for all patches
		final HashMap<TileSpec, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles =
				(HashMap) generateCoefficientsTiles(patches, strategy, numCoefficients * numCoefficients );

		/* completed patches */
		final HashSet<TileSpec> completedPatches = new HashSet<>();

		/* collect patch pairs */
		// find the images that actually overlap (only for those we can extract intensity PointMatches)
		final ArrayList<ValuePair<TileSpec, TileSpec>> patchPairs = new ArrayList<>();

		final double maxDeltaZ = zDistance == null ? Double.MAX_VALUE : zDistance;

		for (final TileSpec p1 : patches) {
			completedPatches.add( p1 );

			final RealInterval r1 = getBoundingBox(p1);

			final ArrayList<TileSpec> p2s = new ArrayList<>();

			for (final TileSpec p2 : patches) {
				final FinalRealInterval i = Intervals.intersect( r1, getBoundingBox(p2) );

				// to make sure zLayers are connected correctly, pad deltaZ with 0.01
				final double deltaZ = Math.abs(p1.getZ() - p2.getZ()) + 0.01;
				if ( i.realMax( 0 ) - i.realMin( 0 ) > 0 &&
					 i.realMax( 1 ) - i.realMin( 1 ) > 0 &&
					 deltaZ < maxDeltaZ)
				{
					// TODO: test in z, only if they are close enough in z connect them
					p2s.add( p2 );
				}
			}

			for (final TileSpec p2 : p2s) {
				/*
				 * if this patch had been processed earlier, all matches are
				 * already in
				 */
				if ( completedPatches.contains( p2 ) )
					continue;

				patchPairs.add( new ValuePair<>( p1, p2 ) );
//				System.out.println( p1.getImageCol() + " <> " + p2.getImageCol() );
			}
		}

		LOG.info("match: found {} pairs for {} patches with zDistance {}",
				 patchPairs.size(), patches.size(), zDistance);

		return getOnTheFlyIntensities(patches,
									  scale,
									  numCoefficients,
									  strategy,
									  neighborWeight,
									  iterations,
									  imageProcessorCache,
									  numThreads,
									  filter,
									  coefficientsTiles,
									  completedPatches,
									  patchPairs);

		/*
		final double[] ab = new double[ 2 ];

		List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();

		// iterate in the same order as the input
		for ( final TileSpec p : patches )
		//for ( final Entry< Pair<AffineModel2D,TileSpec>, ArrayList< Tile< ? extends M > > > entry : coefficientsTiles.entrySet() )
		{
			//final Pair<AffineModel2D,TileSpec> p = entry.getKey();
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

	public ArrayList<OnTheFlyIntensity> matchPairs(
			final List<ValuePair<TileSpec, TileSpec>> patchPairs,
			final double scale,
			final int numCoefficients,
			final IntensityCorrectionStrategy strategy,
			final double neighborWeight,
			final int iterations,
			final ImageProcessorCache imageProcessorCache,
			final int numThreads) throws InterruptedException, ExecutionException
	{
		LOG.info("matchPairs: entry, processing {} pairs", patchPairs.size());

		final List<TileSpec> patches = new ArrayList<>();
		final HashSet<TileSpec> completedPatches = new HashSet<>();
		patchPairs.forEach(pp -> {
			if (! completedPatches.contains(pp.a)) {
				patches.add(pp.a);
				completedPatches.add(pp.a);
			}
			if (! completedPatches.contains(pp.b)) {
				patches.add(pp.b);
				completedPatches.add(pp.b);
			}
		});

		LOG.info("matchPairs: found {} distinct tiles", patches.size());

		final PointMatchFilter filter = strategy.provideOutlierRemoval();

		// generate coefficient tiles for all patches
		final HashMap<TileSpec, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles =
				(HashMap) generateCoefficientsTiles(patches, strategy, numCoefficients * numCoefficients );

		return getOnTheFlyIntensities(patches,
									  scale,
									  numCoefficients,
									  strategy,
									  neighborWeight,
									  iterations,
									  imageProcessorCache,
									  numThreads,
									  filter,
									  coefficientsTiles,
									  completedPatches,
									  patchPairs);
	}

	private static ArrayList<OnTheFlyIntensity> getOnTheFlyIntensities(final List<TileSpec> patches,
																	   final double scale,
																	   final int numCoefficients,
																	   final IntensityCorrectionStrategy strategy,
																	   final double neighborWeight,
																	   final int iterations,
																	   final ImageProcessorCache imageProcessorCache,
																	   final int numThreads,
																	   final PointMatchFilter filter,
																	   final HashMap<TileSpec, ArrayList<Tile<? extends Affine1D<?>>>> coefficientsTiles,
																	   final HashSet<TileSpec> completedPatches,
																	   final List<ValuePair<TileSpec, TileSpec>> patchPairs)
			throws InterruptedException, ExecutionException {
		final int meshResolution = !patches.isEmpty() ? (int) patches.get(0).getMeshCellSize() : 64;

		LOG.info("getOnTheFlyIntensities: entry, matching intensities for {} pairs using {} threads",
				 patchPairs.size(), numThreads);

		// for all pairs of images that do overlap, extract matching intensity values (intensity values that should be the same)
		// TODO: parallelize on SPARK
		final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
		final ArrayList< Future< ? > > futures = new ArrayList<>();
		for ( final ValuePair<TileSpec, TileSpec> patchPair : patchPairs)
		{
			futures.add(
					exec.submit(
							new Matcher(
									patchPair,
									(HashMap) coefficientsTiles,
									filter,
									scale,
									numCoefficients,
									meshResolution,
									imageProcessorCache)) );
		}

		for ( final Future< ? > future : futures )
			future.get();

		LOG.info("getOnTheFlyIntensities: after matching, imageProcessorCache stats are: {}", imageProcessorCache.getStats());

		/* connect tiles within patches */
		for (final TileSpec p1 : completedPatches) {
			/* get the coefficient tiles */
			final ArrayList<? extends Tile<?>> p1CoefficientsTiles = coefficientsTiles.get(p1 );

			for (int y = 1; y < numCoefficients; ++y )
			{
				final int yr = numCoefficients * y;
				final int yr1 = yr - numCoefficients;
				for (int x = 0; x < numCoefficients; ++x )
				{
					identityConnect(p1CoefficientsTiles.get( yr1 + x ), p1CoefficientsTiles.get( yr + x ), neighborWeight);
				}
			}
			for (int y = 0; y < numCoefficients; ++y )
			{
				final int yr = numCoefficients * y;
				for (int x = 1; x < numCoefficients; ++x )
				{
					final int yrx = yr + x;
					identityConnect(p1CoefficientsTiles.get( yrx ), p1CoefficientsTiles.get( yrx - 1 ), neighborWeight);
				}
			}
		}

		/* optimize */
		final TileConfiguration tc = new TileConfiguration();
		for ( final ArrayList<? extends Tile<?>> coefficients : coefficientsTiles.values() )
		{
			// for ( final Tile< ? > t : coefficients )
			// if ( t.getMatches().size() == 0 )
			// IJ.log( "bang" );
			tc.addTiles( coefficients );
		}

		LOG.info("getOnTheFlyIntensities: optimizing {} tiles", tc.getTiles().size());

		try {
			//final ErrorStatistic observer = new ErrorStatistic( iterations + 1 );
			//tc.optimizeSilentlyConcurrent( observer, 0.01f, iterations, iterations, 0.75f );

			TileUtil.optimizeConcurrently(new ErrorStatistic(iterations + 1 ), 0.01f, iterations, iterations, 0.75f,
										  tc, tc.getTiles(), tc.getFixedTiles(), 1 );

			//tc.optimize( 0.01f, iterations, iterations, 0.75f );
		} catch (final Exception e) {
			LOG.error("getOnTheFlyIntensities: error optimizing tiles", e);
		}

		final ArrayList<OnTheFlyIntensity> onTheFlyIntensities = strategy.getOnTheFlyIntensities(patches,
																								 numCoefficients,
																								 coefficientsTiles);

		LOG.info("getOnTheFlyIntensities: exit, returning intensity coefficients for {} tiles", onTheFlyIntensities.size());

		return onTheFlyIntensities;
	}

	static protected void identityConnect(final Tile<?> t1, final Tile<?> t2, final double weight)
	{
		final ArrayList< PointMatch > matches = new ArrayList<>();
		matches.add( new PointMatch( new Point( new double[] { 0 } ), new Point( new double[] { 0 } ) ) );
		matches.add( new PointMatch( new Point( new double[] { 1 } ), new Point( new double[] { 1 } ) ) );
		t1.connect( t2, matches );
	}

	public static RealInterval getBoundingBox(final TileSpec tileSpec)
	{
		final double[] p1min = new double[]{ tileSpec.getMinX(), tileSpec.getMinY() };
		final double[] p1max = new double[]{ tileSpec.getMaxX(), tileSpec.getMaxY() };

		/*
		final double[] p1min = new double[]{ 0,0 };
		final double[] p1max = new double[]{ m.getWidth() - 1, m.getHeight() - 1 };
		t.estimateBounds( p1min, p1max );
		*/

		return new FinalRealInterval(p1min, p1max);
	}

	static protected <T extends Model<T> & Affine1D<T>> HashMap<TileSpec, ArrayList<Tile<T>>> generateCoefficientsTiles(
			final Collection<TileSpec> patches,
			final IntensityCorrectionStrategy provider,
			final int nCoefficients)
	{
		final HashMap<TileSpec, ArrayList< Tile< T > > > map = new HashMap<>();
		for (final TileSpec p : patches) {
			final ArrayList< Tile< T > > coefficientModels = new ArrayList<>();
			for ( int i = 0; i < nCoefficients; ++i )
				coefficientModels.add(new Tile<T>(provider.getModelFor(p)));

			map.put( p, coefficientModels );
		}
		return map;
	}

	private static final Logger LOG = LoggerFactory.getLogger(IntensityMatcher.class);
}
