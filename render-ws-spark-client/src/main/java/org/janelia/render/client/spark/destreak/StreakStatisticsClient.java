package org.janelia.render.client.spark.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.CoordinateTransformList;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.destreak.StreakFinder;
import org.janelia.alignment.loader.ImageJCompositeLoader;
import org.janelia.alignment.loader.ImageJDefaultLoader;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class StreakStatisticsClient implements Serializable {

		public static class Parameters extends CommandLineParameters {

			@ParametersDelegate
			public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

			@Parameter(names = "--stack", description = "Stack to pull image and transformation data from", required = true)
			public String stack;

			@Parameter(names = "--threshold", description = "Threshold used to convert the streak mask to a binary mask", required = true)
			public double threshold;

			@Parameter(names = "--blurRadius", description = "Radius of the Gaussian blur applied to the streak mask", required = true)
			private int blurRadius;

			@Parameter(names = "--meanFilterSize", description = "Number of pixels to average in the y-direction (must be odd)", required = true)
			private int meanFilterSize;

			@Parameter(names = "--output", description = "Output file path", required = true)
			public String outputPath;

			@Parameter(names = "--nCells", description = "Number of cells to use in x and y directions, e.g., 5x3", required = true)
			public String cells;

			private int nX = -1;
			private int nY = -1;

			public void validate() {
				if (threshold < 1) {
					throw new IllegalArgumentException("threshold must be positive");
				}
				if (blurRadius < 1) {
					throw new IllegalArgumentException("blurRadius must be positive");
				}
				if (meanFilterSize < 1 || meanFilterSize % 2 == 0) {
					throw new IllegalArgumentException("meanFilterSize must be positive and odd");
				}
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
				if (nX == -1) {
					validate();
				}
				return nX;
			}

			public int nCellsY() {
				if (nY == -1) {
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
					client.run();
				}
			};
			clientRunner.run();
		}


	private static final Logger LOG = LoggerFactory.getLogger(StreakStatisticsClient.class);
	private final Parameters parameters;

	public StreakStatisticsClient(final Parameters parameters) {
		this.parameters = parameters;
	}

	public void run() throws IOException {
		final SparkConf conf = new SparkConf().setAppName("StreakStatisticsClient");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);

		final String sparkAppId = sparkContext.getConf().getAppId();
		final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

		LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

		final RenderDataClient dataClient = parameters.renderWeb.getDataClient();
		final StackMetaData stackMetaData = dataClient.getStackMetaData(parameters.stack);
		final Broadcast<Bounds> stackBounds = sparkContext.broadcast(stackMetaData.getStackBounds());

		final Broadcast<StreakFinder> streakFinder = sparkContext.broadcast(new StreakFinder(parameters.meanFilterSize, parameters.threshold, parameters.blurRadius));

		final ResolvedTileSpecCollection rtsc = dataClient.getResolvedTilesForZRange(parameters.stack, stackBounds.value().getMinZ(), stackBounds.value().getMaxZ());
		rtsc.resolveTileSpecs();
		LOG.info("run: fetched {} resolved tiles for stack {}", rtsc.getTileCount(), parameters.stack);

		final Map<String, Set<String>> zLayerToTileIds = rtsc.buildSectionIdToTileIdsMap();
		final List<Tuple2<Double, List<TileSpec>>> zLayerToTileSpecs = new ArrayList<>(zLayerToTileIds.size());
		zLayerToTileIds.forEach((zLayer, tileIds) -> {
			final List<TileSpec> tileSpecs = new ArrayList<>(tileIds.size());
			tileIds.forEach(tileId -> tileSpecs.add(rtsc.getTileSpec(tileId)));
			zLayerToTileSpecs.add(new Tuple2<>(Double.valueOf(zLayer), tileSpecs));
		});

		final List<Tuple2<Double, double[][]>> result = sparkContext.parallelizePairs(zLayerToTileSpecs)
				.mapValues(tileSpecs -> computeStreakStatisticsForLayer(streakFinder.value(), stackBounds.value(), parameters.nCellsX(), parameters.nCellsY(), tileSpecs))
				.collect();

		final List<Tuple2<Double, double[][]>> zLayerToStreakStatistics = new ArrayList<>(result);
		zLayerToStreakStatistics.sort((a, b) -> a._1.compareTo(b._1));
		final double[] fullData = new double[parameters.nCellsX() * parameters.nCellsY() * zLayerToStreakStatistics.size()];
		final Img<DoubleType> data = ArrayImgs.doubles(fullData, parameters.nCellsX(), parameters.nCellsY(), zLayerToStreakStatistics.size());
		final long[] position = new long[3];
		int z = 0;
		for (final Tuple2<Double, double[][]> zLayerToStreakStatistic : zLayerToStreakStatistics) {
			final Double zLayer = zLayerToStreakStatistic._1;
			final double[][] layerStatistics = zLayerToStreakStatistic._2;
			position[2] = z;
			for (int x = 0; x < parameters.nCellsX(); x++) {
				position[0] = x;
				for (int y = 0; y < parameters.nCellsY(); y++) {
					position[1] = y;
					data.getAt(position).set(layerStatistics[x][y]);
				}
			}
			z++;
		}

		final RandomAccessibleInterval<DoubleType> transposedData = Views.permute(data, 0, 2);
		final N5Writer n5Writer = new N5ZarrWriter(parameters.outputPath);
		final String dataset = Paths.get(parameters.renderWeb.project, parameters.stack).toString();
		final int[] fullDimensions = Arrays.stream(transposedData.dimensionsAsLongArray()).mapToInt(i -> (int) i).toArray();
		N5Utils.save(transposedData, n5Writer, dataset, fullDimensions, new GzipCompression());

		final double[] min = new double[3];
		min[0] = stackBounds.value().getMinX();
		min[1] = stackBounds.value().getMinY();
		min[2] = stackBounds.value().getMinZ();
		n5Writer.setAttribute(dataset, "stackMin", min);

		final double[] max = new double[3];
		max[0] = stackBounds.value().getMaxX();
		max[1] = stackBounds.value().getMaxY();
		max[2] = stackBounds.value().getMaxZ();
		n5Writer.setAttribute(dataset, "stackMax", max);

		sparkContext.close();
	}

	private static double[][] computeStreakStatisticsForLayer(
			final StreakFinder streakFinder,
			final Bounds stackBounds,
			final int nX,
			final int nY,
			final List<TileSpec> layerTiles) {

		LOG.info("computeStreakStatisticsForLayer: processing {} tiles", layerTiles.size());
		final StreakAccumulator accumulator = new StreakAccumulator(stackBounds, nX, nY);

		for (final TileSpec tileSpec : layerTiles) {
			final int z = tileSpec.getIntegerZ();
			if (z > 1) {
				continue;
			}
			final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;
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
					results[i][j] = sum[i][j] / counts[i][j];
				}
			}
			return results;
		}
	}
}
