package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.filter.BackgroundCorrectionFilter;
import org.janelia.alignment.filter.Filter;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Create and store a background image that can be used to subtract a
 * spatially varying background from a set of images.
 * Originally developed for the CellMap jrc_mus_thymus_1 dataset.
 *
 * @author Michael Innerberger
 */
public class BackgroundCorrectionClient {

	private final Parameters params;
	private final RenderDataClient renderClient;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final ZRangeParameters zRangeParams = new ZRangeParameters();
		@Parameter(names = "--stack", description = "Stack for which to correct background", required = true)
		private String stack;
		@Parameter(names = "--regex", description = "Regular expression for matching tiles to correct background for; all tiles are corrected if not given")
		private String regex = null;
		@Parameter(names = "--radius", description = "Radius for median filter in pixels (default: 50.0)")
		private double radius = 50.0;
		@Parameter(names = "--scale", description = "Scale to use for median filter (default: 0.1)")
		private double scale = 0.1;
		@Parameter(names = "--outputFolder", description = "Folder to write corrected images to (default: ./background_corrected)")
		private String outputFolder = "background_corrected";
	}

	public static void main(String[] args) {

		if (args.length == 0) {
			args = new String[] {
					"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "cellmap",
					"--project", "jrc_mus_thymus_1",
					"--stack", "v2_acquire_align",
					"--minZ", "1250",
					"--maxZ", "1250",
					"--regex", ".*_0-[01]-1.*",
					"--radius", "1000.0",
					"--scale", "0.05",
			};
		}

		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final BackgroundCorrectionClient client = new BackgroundCorrectionClient(parameters);
				client.correctBackground();
			}
		};
		clientRunner.run();
	}

	public BackgroundCorrectionClient(final Parameters parameters) {
		this.params = parameters;
		this.renderClient = new RenderDataClient(parameters.renderParams.baseDataUrl, parameters.renderParams.owner, parameters.renderParams.project);
	}

	public void correctBackground() {
		final ResolvedTileSpecCollection tileSpecs = getTileSpecs();
		final ImageProcessorCache imageProcessorCache = ImageProcessorCache.DISABLED_CACHE;
		final Filter filter = new BackgroundCorrectionFilter(params.radius, params.scale);

		ensureOutputFolderExists();
		for (final TileSpec tileSpec : tileSpecs.getTileSpecs()) {
			final ImageProcessor ip = loadImage(tileSpec, imageProcessorCache);

			final long start = System.currentTimeMillis();
			filter.process(ip, 1.0);
			final long end = System.currentTimeMillis();
			LOG.info("Corrected background for tile {} in {} ms", tileSpec.getTileId(), end - start);

			saveImage(ip, tileSpec);
		}
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

	private void ensureOutputFolderExists() {
		final Path outputFolder = Path.of(params.outputFolder);
		final boolean folderExists = outputFolder.toFile().exists() || outputFolder.toFile().mkdirs();

		if (!folderExists) {
			LOG.error("Could not create output folder: {}", outputFolder);
			System.exit(1);
		}
	}

	private ImageProcessor loadImage(final TileSpec tileSpec, final ImageProcessorCache imageProcessorCache) {
		final ChannelSpec firstChannelSpec = tileSpec.getAllChannels().get(0);
		final String tileId = tileSpec.getTileId();
		final ImageAndMask imageAndMask = firstChannelSpec.getFirstMipmapImageAndMask(tileId);
		return imageProcessorCache.get(imageAndMask.getImageUrl(),
									   0,
									   false,
									   firstChannelSpec.is16Bit(),
									   imageAndMask.getImageLoaderType(),
									   imageAndMask.getImageSliceNumber());
	}

	private void saveImage(final ImageProcessor ip, final TileSpec tileSpec) {
		final String tileId = tileSpec.getTileId();
		final ImagePlus imp = new ImagePlus(tileId, ip);
		final Path targetPath = Path.of(params.outputFolder, tileId + ".png").toAbsolutePath();
		IJ.save(imp, targetPath.toString());
	}

	private static final Logger LOG = LoggerFactory.getLogger(BackgroundCorrectionClient.class);
}
