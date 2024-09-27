package org.janelia.render.client.spark.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.destreak.StreakFinder;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.StreakFinderParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * This client computes statistics for streaks in a stack of images by accumulating the values of a streak mask over
 * subregions of each layer.
 */
public class StreakStatisticsClient implements Serializable {

		public static class Parameters extends CommandLineParameters {

			@ParametersDelegate
			public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

			@ParametersDelegate
			public ZRangeParameters zRange = new ZRangeParameters();

			@ParametersDelegate
			public StreakFinderParameters streakFinder = new StreakFinderParameters();

			@Parameter(names = "--stack", description = "Stack to pull image and transformation data from", required = true)
			public String stack;

			@Parameter(names = "--output", description = "Output file path", required = true)
			public String outputPath;

			@Parameter(names = "--nCells", description = "Number of cells to use in x and y directions, e.g., 5x3", required = true)
			public String cells;

			private int nX = 0;
			private int nY = 0;

			public void validate() {
				streakFinder.validate();
				try {
					final String[] nCells = cells.split("x");
					nX = Integer.parseInt(nCells[0]);
					nY = Integer.parseInt(nCells[1]);
					if (nX < 1 || nY < 1) {
						throw new IllegalArgumentException("nCells must be positive");
					}
				} catch (final NumberFormatException e) {
					throw new IllegalArgumentException("nCells must be in the format 'NxM'");
				}
			}

			public int nCellsX() {
				if (nX == 0) {
					validate();
				}
				return nX;
			}

			public int nCellsY() {
				if (nY == 0) {
					validate();
				}
				return nY;
			}
		}

		public static void main(final String[] args) {
			final ClientRunner clientRunner = new ClientRunner(args) {
				@Override
				public void runClient(final String[] args) throws Exception {

					final Parameters parameters = new Parameters();
					parameters.parse(args);
					LOG.info("runClient: entry, parameters={}", parameters);
					parameters.validate();

					final StreakStatisticsClient client = new StreakStatisticsClient(parameters);
					client.compileStreakStatistics();
				}
			};
			clientRunner.run();
		}


	private static final Logger LOG = LoggerFactory.getLogger(StreakStatisticsClient.class);
	private final Parameters parameters;

	public StreakStatisticsClient(final Parameters parameters) {
		this.parameters = parameters;
	}

	public void compileStreakStatistics() throws IOException {
		final SparkConf conf = new SparkConf().setAppName("StreakStatisticsClient");

		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
			final String sparkAppId = sparkContext.getConf().getAppId();
			final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

			LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

			compileStreakStatistics(sparkContext);
		}
	}

	private void compileStreakStatistics(final JavaSparkContext sparkContext) throws IOException {
		// get stack and tile data
		final ResolvedTileSpecCollection rtsc = getRequestedTiles();
		final Broadcast<Bounds> stackBounds = sparkContext.broadcast(rtsc.toBounds());
		LOG.info("run: fetched {} resolved tiles for stack {}", rtsc.getTileCount(), parameters.stack);

		// prepare a suitable data structure for spark
		final Map<String, Set<String>> zLayerToTileIds = rtsc.buildSectionIdToTileIdsMap();
		final List<Tuple2<Double, List<TileSpec>>> zLayerToTileSpecs = new ArrayList<>(zLayerToTileIds.size());
		zLayerToTileIds.forEach((zLayer, tileIds) -> {
			final List<TileSpec> tileSpecs = new ArrayList<>(tileIds.size());
			tileIds.forEach(tileId -> tileSpecs.add(rtsc.getTileSpec(tileId)));
			zLayerToTileSpecs.add(new Tuple2<>(Double.valueOf(zLayer), tileSpecs));
		});

		// do the actual computation in a distributed way
		final Broadcast<Map<String, TransformSpec>> referenceSpecs = sparkContext.broadcast(rtsc.getTransformIdToSpecMap());
		final Broadcast<StreakFinder> streakFinder = sparkContext.broadcast(parameters.streakFinder.createStreakFinder());
		final List<Tuple2<Double, double[][]>> result = sparkContext.parallelizePairs(zLayerToTileSpecs)
				.mapValues(tileSpecs -> resolveTileSpecs(referenceSpecs.getValue(), tileSpecs))
				.mapValues(tileSpecs -> computeStreakStatisticsForLayer(streakFinder.value(), stackBounds.value(), parameters.nCellsX(), parameters.nCellsY(), tileSpecs))
				.collect();

		// convert to image and store on disk - list needs to be copied since the list returned by spark is not sortable
		final Img<DoubleType> data = combineToImg(new ArrayList<>(result));
		storeData(data, stackBounds);
	}

	private ResolvedTileSpecCollection getRequestedTiles() throws IOException {
		final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
		final StackMetaData stackMetaData = dataClient.getStackMetaData(parameters.stack);
		final Bounds bounds = stackMetaData.getStackBounds();
		final Bounds reducedBounds = parameters.zRange.overrideBounds(bounds);

		return dataClient.getResolvedTilesForZRange(parameters.stack, reducedBounds.getMinZ(), reducedBounds.getMaxZ());
	}

	private Img<DoubleType> combineToImg(final List<Tuple2<Double, double[][]>> zLayerToStreakStatistics) {
		final double[] fullData = new double[parameters.nCellsX() * parameters.nCellsY() * zLayerToStreakStatistics.size()];
		final Img<DoubleType> data = ArrayImgs.doubles(fullData, parameters.nCellsX(), parameters.nCellsY(), zLayerToStreakStatistics.size());
		final long[] position = new long[3];
		int z = 0;

		zLayerToStreakStatistics.sort((a, b) -> a._1.compareTo(b._1));
		for (final Tuple2<Double, double[][]> zLayerToStreakStatistic : zLayerToStreakStatistics) {
			final double[][] layerStatistics = zLayerToStreakStatistic._2;

			position[2] = z++;
			for (int x = 0; x < parameters.nCellsX(); x++) {
				position[0] = x;
				for (int y = 0; y < parameters.nCellsY(); y++) {
					position[1] = y;
					data.getAt(position).set(layerStatistics[x][y]);
				}
			}
		}
		return data;
	}

	private static List<TileSpec> resolveTileSpecs(final Map<String, TransformSpec> referenceSpecs, final List<TileSpec> tileSpecs) {
		final List<TileSpec> resolvedTileSpecs = new ArrayList<>(tileSpecs.size());
		for (final TileSpec tileSpec : tileSpecs) {
			tileSpec.getTransforms().resolveReferences(referenceSpecs);
			resolvedTileSpecs.add(tileSpec);
		}
		return resolvedTileSpecs;
	}

	private void storeData(final Img<DoubleType> data, final Broadcast<Bounds> stackBounds) {
		// transpose data because images are F-order and python expects C-order
		final RandomAccessibleInterval<DoubleType> transposedData = Views.permute(data, 0, 2);
		final String dataset = Paths.get(parameters.renderWeb.project, parameters.stack).toString();
		final int[] fullDimensions = Arrays.stream(transposedData.dimensionsAsLongArray()).mapToInt(i -> (int) i).toArray();

		final double[] min = new double[3];
		min[0] = stackBounds.value().getMinX();
		min[1] = stackBounds.value().getMinY();
		min[2] = stackBounds.value().getMinZ();

		final double[] max = new double[3];
		max[0] = stackBounds.value().getMaxX();
		max[1] = stackBounds.value().getMaxY();
		max[2] = stackBounds.value().getMaxZ();

		try (final N5Writer n5Writer = new N5ZarrWriter(parameters.outputPath)) {
			N5Utils.save(transposedData, n5Writer, dataset, fullDimensions, new GzipCompression());

			n5Writer.setAttribute(dataset, "StackBounds", Map.of("min", min, "max", max));
			final Map<String, Double> runParameters = Map.of(
					"threshold", parameters.streakFinder.threshold,
					"meanFilterSize", (double) parameters.streakFinder.meanFilterSize,
					"blurRadius", (double) parameters.streakFinder.blurRadius);
			n5Writer.setAttribute(dataset, "RunParameters", runParameters);
		}
	}

	private static double[][] computeStreakStatisticsForLayer(
			final StreakFinder streakFinder,
			final Bounds stackBounds,
			final int nX,
			final int nY,
			final List<TileSpec> layerTiles) {

		LOG.info("computeStreakStatisticsForLayer: processing {} tiles", layerTiles.size());
		final StreakAccumulator accumulator = new StreakAccumulator(stackBounds, nX, nY);
		final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;

		for (final TileSpec tileSpec : layerTiles) {
			LOG.debug("computeStreakStatisticsForLayer: processing tile {}", tileSpec.getTileId());

			final ImageProcessor imp = cache.get(tileSpec.getImagePath(), 0, false, false, ImageLoader.LoaderType.H5_SLICE, null);
			final ImagePlus image = new ImagePlus(tileSpec.getTileId(), imp);
			if (image.getProcessor() == null) {
				LOG.warn("computeStreakStatisticsForLayer: could not load image for tile {}", tileSpec.getTileId());
				continue;
			}

			final ImagePlus mask = streakFinder.createStreakMask(image);
			addStreakStatisticsForSingleMask(accumulator, mask, tileSpec);
		}

		return accumulator.getResults();
	}

	private static void addStreakStatisticsForSingleMask(final StreakAccumulator accumulator, final ImagePlus mask, final TileSpec tileSpec) {
		final double[] position = new double[2];
		final CoordinateTransformList<CoordinateTransform> transformList = tileSpec.getTransformList();
		for (int x = 0; x < mask.getWidth(); x++) {
			for (int y = 0; y < mask.getHeight(); y++) {
				position[0] = x;
				position[1] = y;
				transformList.applyInPlace(position);
				accumulator.addValue(mask.getProcessor().getf(x, y), position[0], position[1]);
			}
		}
	}

	private static class StreakAccumulator {
		private final int nX;
		private final int nY;
		private final Bounds layerBounds;

		private final double[][] sum;
		private final int[][] counts;

		public StreakAccumulator(final Bounds layerBounds, final int nX, final int nY) {
			this.layerBounds = layerBounds;
			this.nX = nX;
			this.nY = nY;
			sum = new double[nX][nY];
			counts = new int[nX][nY];
		}

		public void addValue(final double value, final double x, final double y) {
			final int i = (int) ((x - layerBounds.getMinX()) / layerBounds.getWidth() * nX);
			final int j = (int) ((y - layerBounds.getMinY()) / layerBounds.getHeight() * nY);
			sum[i][j] += value;
			counts[i][j]++;
		}

		public double[][] getResults() {
			final double[][] results = new double[nX][nY];
			for (int i = 0; i < nX; i++) {
				for (int j = 0; j < nY; j++) {
					// invert the result because the mask is 0 where there are streaks and 255 where there are no streaks
					results[i][j] = (255.0 - sum[i][j] / counts[i][j]) / 255.0;
				}
			}
			return results;
		}
	}
}
