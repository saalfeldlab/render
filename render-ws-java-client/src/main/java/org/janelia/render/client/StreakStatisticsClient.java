package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.IJ;
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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Client for computing statistics on streaks (in y-direction) in a stack.
 */
public class StreakStatisticsClient {
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
				names = "--outputFileFormat",
				description = "Output file format for statistics (must contain exactly one %d for the z value)")
		public String outputFileFormat = "streak_statistics_z%d.csv";

		@Parameter(
				names = "--maskStorageLocation",
				description = "Place to store masks (masks are not stored if not given)")
		public String maskStorageLocation = null;
	}

	/**
	 * @param  args  see {@link StreakStatisticsClient.Parameters} for command line argument details.
	 */
	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
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
		// Get tiles (cut at stack boundaries) and meta data
		final double zMin = zValue - parameters.context;
		final double zMax = zValue + parameters.context;
		final ResolvedTileSpecCollection rtsc = renderDataClient.getResolvedTilesForZRange(parameters.stack, zMin, zMax);

		rtsc.recalculateBoundingBoxes();
		final Bounds regionBounds = rtsc.toBounds();
		LOG.info("Region bounds={}", regionBounds);
		final int statisticsWidth = regionBounds.getHeight();

		// Accumulate pixel values for each tile in a global coordinate system orthogonal to streaks with averaging in z
		final double[] pixelwiseSum = new double[statisticsWidth];
		final int[] pixelwiseCount = new int[statisticsWidth];
		for (final TileSpec tileSpec : rtsc.getTileSpecs()) {
			LOG.info("Creating mask for tileId={}", tileSpec.getTileId());

			final ImagePlus streakMask = computeMaskFor(tileSpec);
			if (streakMask == null) {
				throw new RuntimeException("Failed to create mask for tile " + tileSpec.getTileId());
			}
			final ImageProcessor processor = streakMask.getProcessor();

			if (parameters.maskStorageLocation != null) {
				storeMask(zValue, tileSpec, streakMask);
			}

			LOG.info("tile bounds={}", tileSpec.toTileBounds());
			final IntRange recordingRange = getRecordingRange(tileSpec.toTileBounds(), regionBounds, tileSpec.getHeight());
			recordPixelValues(processor, recordingRange, pixelwiseSum, pixelwiseCount);
		}

		// Compute statistics
		final double[] statistics = new double[statisticsWidth];
		for (int i = 0; i < statisticsWidth; i++) {
			if (pixelwiseCount[i] == 0) {
				statistics[i] = 0.0;
			} else {
				statistics[i] = 1 - (pixelwiseSum[i] / pixelwiseCount[i]) / 255.0;
			}
		}

		writeStatistics(zValue, statisticsWidth, regionBounds, statistics);
	}

	private ImagePlus computeMaskFor(final TileSpec tileSpec) {
		final List<ChannelSpec> channels = tileSpec.getAllChannels();
		if (channels.size() != 1) {
			LOG.warn("Skipping tile {} with {} channels (only 1 channel allowed)", tileSpec.getTileId(), channels.size());
			return null;
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
		return streakFinder.createStreakMask(image);
	}

	// Write mask to disk
	private void storeMask(final int zValue, final TileSpec tileSpec, final ImagePlus streakMask) {
		final Path maskPath = Path.of(parameters.maskStorageLocation, String.format("z%05d", zValue), tileSpec.getTileId() + ".png");

		if (maskPath.toFile().exists()) {
			LOG.warn("Mask file {} already exists, not overwriting", maskPath);
		} else {
			final File parentFolder = maskPath.getParent().toFile();
			if (!(parentFolder.exists() || parentFolder.mkdirs())) {
				LOG.warn("Failed to create parent directories for mask file {}", maskPath);
			} else {
				LOG.info("Writing mask to file {}", maskPath);
				IJ.save(streakMask, maskPath.toString());
			}
		}
	}

	// Figure out which part of the statistics index range the tile is responsible for
	private IntRange getRecordingRange(final TileBounds tileBounds, final Bounds bounds, final int size) {
		// Get the ranges in the correct direction
		final IntRange regionRange = new IntRange(bounds.getMinY(), bounds.getMaxY());

		// Adjust the tile range so that it's the same size as the original image's pixel range
		final int pixelsToAdjust = (int) (tileBounds.getDeltaY() - size);
		final int shiftLeft = pixelsToAdjust / 2;
		final int shiftRight = pixelsToAdjust - shiftLeft;
		final IntRange recordingRange = new IntRange(tileBounds.getMinY() + shiftLeft, tileBounds.getMaxY() - shiftRight);

		// Check that the recording range is contained in the region range and make the recording range relative to the region range
		if (!regionRange.containsRange(recordingRange)) {
			throw new IllegalArgumentException("Recording range " + recordingRange + " is not contained in region range " + regionRange);
		}
		return new IntRange(recordingRange.getMinimumInteger() - regionRange.getMinimumInteger(),
							recordingRange.getMaximumInteger() - regionRange.getMinimumInteger());
	}

	// Record mask pixel values in the statistics arrays
	private static void recordPixelValues(
			final ImageProcessor processor,
			final IntRange recordingRange,
			final double[] pixelwiseSum,
			final int[] pixelwiseCount
	) {
		for (int j = 0; j < processor.getHeight(); j++) {
			final int idx = recordingRange.getMinimumInteger() + j;
			for (int i = 0; i < processor.getWidth(); i++) {
				final int value = processor.get(i, j);
				pixelwiseSum[idx] += value;
				pixelwiseCount[idx]++;
			}
		}
	}

	// Write statistics in CSV format with pixel location and mean value as columns
	private void writeStatistics(
			final int zValue,
			final int statisticsWidth,
			final Bounds regionBounds,
			final double[] statistics
	) throws IOException {
		final String outputFile = String.format(parameters.outputFileFormat, zValue);
		LOG.info("Writing statistics to file {}", outputFile);
		try (final FileWriter fileWriter = new FileWriter(outputFile)) {
			fileWriter.write("pixel,mean\n");
			for (int i = 0; i < statisticsWidth; i++) {
				fileWriter.write(String.format("%d,%f\n", (int) (regionBounds.getMinY() + i), statistics[i]));
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(StreakStatisticsClient.class);
}
