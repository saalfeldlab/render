package org.janelia.render.client.spark.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.google.cloud.Tuple;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.destreak.StreakFinder;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

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
		LOG.info("run: fetched {} resolved tiles for stack {}", rtsc.getTileCount(), parameters.stack);

		final Map<String, Set<String>> zLayerToTileIds = rtsc.buildSectionIdToTileIdsMap();
		final List<Tuple2<String, List<TileSpec>>> zLayerToTileSpecs = new ArrayList<>(zLayerToTileIds.size());
		zLayerToTileIds.forEach((zLayer, tileIds) -> {
			final List<TileSpec> tileSpecs = new ArrayList<>(tileIds.size());
			tileIds.forEach(tileId -> tileSpecs.add(rtsc.getTileSpec(tileId)));
			zLayerToTileSpecs.add(new Tuple2<>(zLayer, tileSpecs));
		});

		final List<Tuple2<String, double[][]>> zLayerToStreakStatistics = sparkContext.parallelizePairs(zLayerToTileSpecs)
				.mapValues(tileSpecs -> computeStreakStatisticsForLayer(streakFinder.value(), stackBounds.value(), parameters.nCellsX(), parameters.nCellsY(), tileSpecs))
				.collect();

		int n = 0;
		for (final Tuple2<String, double[][]> zLayerToStreakStatistic : zLayerToStreakStatistics) {
			final String zLayer = zLayerToStreakStatistic._1;
			final double[][] streakStatistics = zLayerToStreakStatistic._2;
			final int nX = parameters.nCellsX();
			final int nY = parameters.nCellsY();
			n += (int) streakStatistics[0][0];
		}
		LOG.info("run: counted {} processed tiles", n);

		sparkContext.close();
	}

	private static double[][] computeStreakStatisticsForLayer(
			final StreakFinder streakFinder,
			final Bounds stackBounds,
			final int nX,
			final int nY,
			final List<TileSpec> layerTiles) {

		// column-major order
		final double[][] results = new double[nX][nY];
		results[0][0] = 1.0;
		return results;
	}
}
