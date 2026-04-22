package org.janelia.render.client.intensityadjust;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

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
import org.janelia.render.client.solver.visualize.RenderTools;

import net.imglib2.Interval;

import static org.janelia.alignment.util.ImageProcessorCache.DEFAULT_MAX_CACHED_PIXELS;

public class AdjustBlock {

	public static List<TileSpec> sortTileSpecs(final ResolvedTileSpecCollection resolvedTiles) {
		// tile order changes adjustment results, so sort tiles by id to ensure (somewhat) consistent results
		return resolvedTiles.getTileSpecs()
				.stream()
				.sorted(Comparator.comparing(TileSpec::getTileId))
				.collect(Collectors.toList());
	}

	public static ImageProcessorWithMasks fuseFinal(
			final RenderParameters sliceRenderParameters,
			final List<TileSpec> data1,
			final ArrayList < OnTheFlyIntensity > corrected1,
			final ImageProcessorCache imageProcessorCache )
	{
		// TODO: pass pre-loaded cache in and clear source data so that masks can be cached and reused across z
		final PreloadedImageProcessorCache preloadedImageProcessorCache =
				new PreloadedImageProcessorCache(DEFAULT_MAX_CACHED_PIXELS,
												 false,
												 false);

		for (int i = 0; i < data1.size(); i++) {
			final TileSpec tileSpec = data1.get(i);

			// this should be a virtual construct
			{
				/*
				final FloatProcessor correctedSource = corrected1.get( i ).computeIntensityCorrectionOnTheFly(imageProcessorCache);
	
				// Need to reset intensity range back to full 8-bit before converting to byte processor!
				correctedSource.setMinAndMax(0, 255);
				final ByteProcessor correctedSource8Bit = correctedSource.convertToByteProcessor();
				*/

				preloadedImageProcessorCache.put(tileSpec.getTileImageUrl(), corrected1.get(i).computeIntensityCorrection8BitOnTheFly(imageProcessorCache));
			}
		}
		// TODO: this will be bigger than 2^31
		return Renderer.renderImageProcessorWithMasks(sliceRenderParameters, preloadedImageProcessorCache);
	}

	public static ArrayList<OnTheFlyIntensity> correctIntensitiesForSliceTiles(
			final List<TileSpec> sliceTiles,
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

	public static ImageProcessorWithMasks renderIntensityAdjustedSliceGlobalPerSlice(
			final ResolvedTileSpecCollection resolvedTiles,
			final RenderParameters sliceRenderParameters,
			final double renderScale,
			final Integer zDistance,
			final ImageProcessorCache imageProcessorCache,
			final int numCoefficients,
			final IntensityCorrectionStrategy strategy,
			final int numThreads) throws InterruptedException, ExecutionException
	{
		final List<TileSpec> tilesForZ = sortTileSpecs(resolvedTiles);
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
//		final List<TileSpec> data = getData(z, renderDataClient, stack);
//		final List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();
//		final HashMap< Integer, double[] > adjustments = new HashMap<>();
//
//		int k = -1;
//		for (final Pair<AffineModel2D, TileSpec> tile : data) {
//			++k;
//			final TileSpec minimalTileSpec = tile.getB();
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
//		final List<TileSpec> data = getData(z, renderDataClient, stack);
//		final List<Pair<ByteProcessor, FloatProcessor>> corrected = new ArrayList<>();
//
//		for ( final Pair<AffineModel2D,TileSpec> tile : data )
//		{
//			final TileSpec minimalTileSpec = tile.getB();
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

	public static void main(final String[] args) throws IOException, InterruptedException, ExecutionException
	{
		final String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
		final String owner = "Z0720_07m_BR"; //"flyem";
		final String project = "Sec39";//"Sec26"; //"Z0419_25_Alpha3";
		final String stack = "v1_acquire_trimmed_sp1";//"v2_acquire_trimmed_align"; //"v1_acquire_sp_nodyn_v2";

		final RenderDataClient renderDataClient = new RenderDataClient(baseUrl, owner, project );
		final StackMetaData meta =  renderDataClient.getStackMetaData( stack );
		final Bounds stackBounds = meta.getStats().getStackBounds();
		final Interval interval = RenderTools.stackBounds( meta );

		final int minZ = 23850;//27759;//20000;
		final int maxZ = 23850;//27759;//20000;
		final double stackScale = 1.0; // only full res supported right now

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
															   DEFAULT_NUM_COEFFICIENTS,
															   new AffineIntensityCorrectionStrategy(),
															   1);
			stack3d.addSlice( slice.ip );
		}

		final ImagePlus imp1 = new ImagePlus( project + "_" + stack, stack3d );

		final Calibration cal = new Calibration();
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

}
