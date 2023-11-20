package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.intensityadjust.intensity.Render;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.IOException;

/**
 * Create and store a background image that can be used to subtract a
 * spatially varying background from a set of images.
 */
public class BackgroundComputationClient {

	private final Parameters params;
	private final RenderDataClient renderClient;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final ZRangeParameters zRangeParams = new ZRangeParameters();
		@Parameter(names = "--stack", description = "Stack for which to compute background", required = true)
		private String stack;
		@Parameter(names = "--regex", description = "Regular expression for matching tiles to use for background computation; all tiles are used if not given")
		private String regex = null;
		@Parameter(names = "--scale", description = "Scale factor for background image (default: 1.0)")
		private double scale = 1.0;
		@Parameter(names = "--fileName", description = "Name of file to write background image to (default: background_<stack>.png)")
		private String fileName = null;

		public String getFileName() {
			if (fileName == null) {
				fileName = "background_" + stack + ".png";
			}
			return fileName;
		}
	}

	public static void main(String[] args) {

		if (args.length == 0) {
			args = new String[] {
					"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "cellmap",
					"--project", "jrc_mus_thymus_1",
					"--stack", "v2_acquire_align",
					"--minZ", "1250",
					"--maxZ", "1253",
					"--regex", ".*_0-[01]-1.*",
					"--scale", "0.5",
					"--fileName", "background_test.png"
			};
		}

		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final BackgroundComputationClient client = new BackgroundComputationClient(parameters);
				client.computeBackground();
			}
		};
		clientRunner.run();
	}

	public BackgroundComputationClient(final Parameters parameters) {
		this.params = parameters;
		this.renderClient = new RenderDataClient(parameters.renderParams.baseDataUrl, parameters.renderParams.owner, parameters.renderParams.project);
	}

	public void computeBackground() {
		final ResolvedTileSpecCollection tileSpecs = getTileSpecs();
		final Rectangle boundingBox = tileSpecs.toBounds().toRectangle();
		final int meshResolution = (int) tileSpecs.getTileSpecs().iterator().next().getMeshCellSize();
		final ImageProcessorCache imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;

		final int w = (int) (boundingBox.width * params.scale + 0.5);
		final int h = (int) (boundingBox.height * params.scale + 0.5);

		final FloatProcessor averagePixels = new FloatProcessor(w, h);
		final ColorProcessor numberOfPixels = new ColorProcessor(w, h);

		final FloatProcessor pixels = new FloatProcessor(w, h);
		final FloatProcessor unused = new FloatProcessor(w, h);
		final ColorProcessor isInImage = new ColorProcessor(w, h);

		for (final TileSpec tileSpec : tileSpecs.getTileSpecs()) {
			final MinimalTileSpecWrapper p = new MinimalTileSpecWrapper(tileSpec);

			Render.render(p, 1, 1, pixels, unused, isInImage, boundingBox.x, boundingBox.y, params.scale, meshResolution, imageProcessorCache);
			accumulateIntensities(averagePixels, numberOfPixels, pixels, isInImage);
		}

		divideByNumberOfPixels(averagePixels, numberOfPixels);

		IJ.save(new ImagePlus("Background", averagePixels), params.getFileName());
	}

	private ResolvedTileSpecCollection getTileSpecs() {
		ResolvedTileSpecCollection tileSpecs = null;
		try {
			tileSpecs = renderClient.getResolvedTiles(params.stack,
													  params.zRangeParams.minZ,
													  params.zRangeParams.maxZ,
													  null,
													  null,
													  null,
													  null,
													  null,
													  params.regex);
		} catch (final IOException e) {
			LOG.error("Could not get tile specs: ", e);
			System.exit(1);
		}
		return tileSpecs;
	}

	private static void accumulateIntensities(final FloatProcessor averagePixels, final ColorProcessor numberOfPixels, final FloatProcessor pixels, final ColorProcessor isInImage) {
		final int w = averagePixels.getWidth();
		final int h = averagePixels.getHeight();

		for (int y = 0; y < h; ++y) {
			for (int x = 0; x < w; ++x) {
				if (isInImage.get(x, y) != 0) {
					// record intensities for regions filled by the actual image
					averagePixels.setf(x, y, averagePixels.getf(x, y) + pixels.getf(x, y));
					numberOfPixels.set(x, y, numberOfPixels.get(x, y) + 1);
				}
				// reset intensities to re-use these processors
				pixels.setf(x, y, 0.0f);
				isInImage.set(x, y, 0);
			}
		}
	}

	private static void divideByNumberOfPixels(final FloatProcessor averagePixels, final ColorProcessor numberOfPixels) {
		final int w = averagePixels.getWidth();
		final int h = averagePixels.getHeight();

		for (int y = 0; y < h; ++y) {
			for (int x = 0; x < w; ++x) {
				final int n = numberOfPixels.get(x, y);
				if (n != 0) {
					averagePixels.setf(x, y, averagePixels.getf(x, y) / n);
				}
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(BackgroundComputationClient.class);
}
