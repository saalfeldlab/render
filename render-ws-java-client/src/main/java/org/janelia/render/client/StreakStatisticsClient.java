package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.apache.commons.lang.math.IntRange;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.destreak.StreakFinder;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.StreakFinderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client for computing statistics on streaks in a stack.
 */
public class StreakStatisticsClient {
	public enum Direction {X, Y}

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		public StreakFinderParameters streakFinder = new StreakFinderParameters();

		@ParametersDelegate
		public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

		@Parameter(
				names = "--stack",
				description = "Stack name",
				required = true)
		public String stack;

		@Parameter(
				names = "--zValues",
				description = "Z values to compute statistics for",
				variableArity = true)
		public List<Integer> zValues;

		@Parameter(
				names = "--context",
				description = "Number of z slices to include in the computation (above and below the chosen z value)")
		public Integer context = 3;

		@Parameter(
				names = "--streakDirection",
				description = "Direction of the streaks (X or Y)")
		public Direction streakDirection = Direction.Y;

		@Parameter(
				names = "--outputFileFormat",
				description = "Output file format for statistics (must contain exactly one %d for the z value)")
		public String outputFileFormat = "streak_statistics_z%d.csv";

		@Parameter(
				names = "--storeMasks",
				description = "Store masks for debugging")
		public boolean storeMasks = false;
	}

	/**
	 * @param  args  see {@link StreakStatisticsClient.Parameters} for command line argument details.
	 */
	public static void main(final String[] args) {
		final String[] testArgs = {
				"--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
				"--owner", "cellmap",
				"--project", "jrc_mus_pancreas_5",
				"--stack", "v3_acquire_align",
				"--zValues", "10",
				"--context", "3",
				"--streakDirection", "Y",
				"--outputFileFormat", "streak_statistics.csv",
				"--meanFilterSize", "201",
				"--threshold", "10.0",
				"--blurRadius", "3"
		};

		final ClientRunner clientRunner = new ClientRunner(testArgs) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final StreakStatisticsClient client = new StreakStatisticsClient(parameters);
				client.computeStreakStatistics();
			}
		};
		clientRunner.run();
	}


	private final Parameters parameters;
	private final RenderDataClient renderDataClient;
	private final StreakFinder streakFinder;

	public StreakStatisticsClient(final Parameters parameters) {
		this.parameters = parameters;
		this.renderDataClient = parameters.renderWeb.getDataClient();
		this.streakFinder = parameters.streakFinder.createStreakFinder();
	}

	public void computeStreakStatistics() throws IOException {
		for (final Integer zValue : parameters.zValues) {
			computeStreakStatisticsForZValue(zValue);
		}
	}

	private void computeStreakStatisticsForZValue(final int zValue) throws IOException {
		// Get tiles (cut at stack boundaries)
		final double zMin = zValue - parameters.context;
		final double zMax = zValue + parameters.context;
		final ResolvedTileSpecCollection rtsc = renderDataClient.getResolvedTilesForZRange(parameters.stack, zMin, zMax);
		final Map<String, Set<String>> zToTileIds = rtsc.buildSectionIdToTileIdsMap();

		rtsc.recalculateBoundingBoxes();
		final Bounds regionBounds = rtsc.toBounds();
		LOG.info("Region bounds={}", regionBounds);
		final int statisticsWidth = (parameters.streakDirection == Direction.Y) ? regionBounds.getHeight() : regionBounds.getWidth();

		final double[] pixelwiseSum = new double[statisticsWidth];
		final int[] pixelwiseCount = new int[statisticsWidth];
		for (final TileSpec tileSpec : rtsc.getTileSpecs()) {
			LOG.info("Creating mask for tileId={}", tileSpec.getTileId());
			final String tileImageUrl = tileSpec.getTileImageUrl();

			final List<ChannelSpec> channels = tileSpec.getAllChannels();
			if (channels.size() != 1) {
				LOG.warn("Skipping tile {} with {} channels (only 1 channel allowed)", tileSpec.getTileId(), channels.size());
				continue;
			}
			final ChannelSpec channelSpec = channels.get(0);
			final ImageAndMask imageAndMask = channelSpec.getFirstMipmapImageAndMask(tileSpec.getTileId());

			final ImageProcessor sourceImageProcessor =
					ImageProcessorCache.DISABLED_CACHE.get(imageAndMask.getImageUrl(),
														   0,
														   false,
														   false,
														   imageAndMask.getImageLoaderType(),
														   imageAndMask.getImageSliceNumber());

			final ImagePlus image = new ImagePlus("Image", sourceImageProcessor);
			final ImagePlus streakMask = streakFinder.createStreakMask(image);
			final ImageProcessor processor = streakMask.getProcessor();

			LOG.info("tile bounds={}", tileSpec.toTileBounds());
			final IntRange recordingRange = getRecordingRange(tileSpec.toTileBounds(), regionBounds, tileSpec.getHeight(), parameters.streakDirection);
			for (int j = 0; j < processor.getHeight(); j++) {
				final int idx = recordingRange.getMinimumInteger() + j;
				for (int i = 0; i < processor.getWidth(); i++) {
					final int value = processor.get(i, j);
					pixelwiseSum[idx] += value;
					pixelwiseCount[idx]++;
				}
			}

		}

		// Compute statistics by summing streak masks orthogonal to streaks and averaging in z
		final double[] statistics = new double[statisticsWidth];
		for (int i = 0; i < statisticsWidth; i++) {
			if (pixelwiseCount[i] == 0) {
				statistics[i] = 0.0;
			} else {
				statistics[i] = pixelwiseSum[i] / pixelwiseCount[i];
			}
		}

		// Write statistics to file
		final String outputFile = String.format(parameters.outputFileFormat, zValue);
		LOG.info("Writing statistics to file {}", outputFile);
		try (final FileWriter fileWriter = new FileWriter(outputFile)) {
			fileWriter.write("pixel,mean\n");
			for (int i = 0; i < statisticsWidth; i++) {
				fileWriter.write(String.format("%d,%f", (int) (regionBounds.getMinY() + i), statistics[i]));
			}
		}
	}

	// Figure out which part of the statistics index range the tile is responsible for
	private IntRange getRecordingRange(final TileBounds tileBounds, final Bounds bounds, final int size, final Direction direction) {
		// Get the ranges in the correct direction
		final IntRange tileRange = (direction == Direction.Y)
				? new IntRange(tileBounds.getMinY(), tileBounds.getMaxY())
				: new IntRange(tileBounds.getMinX(), tileBounds.getMaxX());
		final IntRange regionRange = (direction == Direction.Y)
				? new IntRange(bounds.getMinY(), bounds.getMaxY())
				: new IntRange(bounds.getMinX(), bounds.getMaxX());

		// Adjust the tile range so that it's the same size as the original image's pixel range
		final int pixelsToAdjust = (int) (tileRange.getMaximumInteger() - tileRange.getMinimumInteger() - size);
		final int shiftLeft = pixelsToAdjust / 2;
		final int shiftRight = pixelsToAdjust - shiftLeft;
		final IntRange recordingRange = new IntRange(tileRange.getMinimumInteger() + shiftLeft, tileRange.getMaximumInteger() - shiftRight);

		// Check that the recording range is contained in the region range and make the recording range relative to the region range
		if (!regionRange.containsRange(recordingRange)) {
			throw new IllegalArgumentException("Recording range " + recordingRange + " is not contained in region range " + regionRange);
		}
		return new IntRange(recordingRange.getMinimumInteger() - regionRange.getMinimumInteger(),
							recordingRange.getMaximumInteger() - regionRange.getMinimumInteger());
	}


	private static final Logger LOG = LoggerFactory.getLogger(StreakStatisticsClient.class);
}
