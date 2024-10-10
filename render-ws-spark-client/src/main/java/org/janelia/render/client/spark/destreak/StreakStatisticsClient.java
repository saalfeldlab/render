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
import java.util.stream.Collectors;
import java.util.stream.IntStream;


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
		// get some metadata and broadcast variables accessed by all workers
		final Broadcast<Parameters> bcParameters = sparkContext.broadcast(parameters);
		final Broadcast<Bounds> bounds = sparkContext.broadcast(getBounds());
		final List<Double> zValues = IntStream.range(bounds.value().getMinZ().intValue(), bounds.value().getMaxZ().intValue() + 1)
				.boxed().map(Double::valueOf).collect(Collectors.toList());

		LOG.info("run: fetched {} z-values for stack {}", zValues.size(), parameters.stack);

		// do the computation in a distributed way
		final List<Tuple2<Double, double[][]>> result = sparkContext.parallelize(zValues)
				.mapToPair(z -> new Tuple2<>(z, pullTileSpecs(bcParameters.value(), z)))
				.mapValues(tileSpecs -> computeStreakStatisticsForLayer(bcParameters.value(), bounds.value(), tileSpecs))
				.collect();

		// convert to image and store on disk - list needs to be copied since the list returned by spark is not sortable
		final Img<DoubleType> data = combineToImg(new ArrayList<>(result));
		storeData(data, bounds.value());
	}

	private Bounds getBounds() throws IOException {
		final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
		final StackMetaData stackMetaData = dataClient.getStackMetaData(parameters.stack);
		return parameters.zRange.overrideBounds(stackMetaData.getStackBounds());
	}

	private static List<TileSpec> pullTileSpecs(final Parameters parameters, final double z) throws IOException {
		final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
		final ResolvedTileSpecCollection rtsc = dataClient.getResolvedTiles(parameters.stack, z);
		return new ArrayList<>(rtsc.getTileSpecs());
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

	private void storeData(final Img<DoubleType> data, final Bounds stackBounds) {
		// transpose data because images are F-order and python expects C-order
		final RandomAccessibleInterval<DoubleType> transposedData = Views.permute(data, 0, 2);
		final String dataset = Paths.get(parameters.renderWeb.project, parameters.stack).toString();
		final int[] fullDimensions = Arrays.stream(transposedData.dimensionsAsLongArray()).mapToInt(i -> (int) i).toArray();

		final double[] min = new double[3];
		min[0] = stackBounds.getMinX();
		min[1] = stackBounds.getMinY();
		min[2] = stackBounds.getMinZ();

		final double[] max = new double[3];
		max[0] = stackBounds.getMaxX();
		max[1] = stackBounds.getMaxY();
		max[2] = stackBounds.getMaxZ();

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
			final Parameters parameters,
			final Bounds stackBounds,
			final List<TileSpec> layerTiles) {

		LOG.info("computeStreakStatisticsForLayer: processing {} tiles", layerTiles.size());
		final StreakAccumulator accumulator = new StreakAccumulator(stackBounds, parameters.nCellsX(), parameters.nCellsY());
		final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;

		for (final TileSpec tileSpec : layerTiles) {
			LOG.debug("computeStreakStatisticsForLayer: processing tile {}", tileSpec.getTileId());

			final ImageProcessor imp = cache.get(tileSpec.getImagePath(), 0, false, false, ImageLoader.LoaderType.H5_SLICE, null);
			final ImagePlus image = new ImagePlus(tileSpec.getTileId(), imp);
			if (image.getProcessor() == null) {
				LOG.warn("computeStreakStatisticsForLayer: could not load image for tile {}", tileSpec.getTileId());
				continue;
			}

			final StreakFinder streakFinder = parameters.streakFinder.createStreakFinder();
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

		private final int minX;
		private final int minY;
		private final int width;
		private final int height;

		private final double[][] sum;
		private final long[][] counts;

		public StreakAccumulator(final Bounds layerBounds, final int nX, final int nY) {
			this.nX = nX;
			this.nY = nY;

			this.minX =  layerBounds.getMinX().intValue();
			this.minY =  layerBounds.getMinY().intValue();
			this.width =  layerBounds.getWidth();
			this.height =  layerBounds.getHeight();

			sum = new double[nX][nY];
			counts = new long[nX][nY];
		}

		public void addValue(final double value, final double x, final double y) {
			final int i = (int) (nX * (x - minX) / width);
			final int j = (int) (nY * (y - minY) / height);
			sum[i][j] += value;
			counts[i][j]++;
		}

		public double[][] getResults() {
			final double[][] results = new double[nX][nY];
			for (int i = 0; i < nX; i++) {
				for (int j = 0; j < nY; j++) {
					// account for the fact that the mask values are in [0, 255], with 255 indicating a streak
					if (counts[i][j] == 0) {
						results[i][j] = 0;
					} else {
						results[i][j] = sum[i][j] / (255 * counts[i][j]);
					}
				}
			}
			return results;
		}
	}
}
