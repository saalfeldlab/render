package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
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
		final Rectangle backgroundBounds = tileSpecs.toBounds().toRectangle();

		System.out.println("Fetched " + tileSpecs.getTileCount() + " tile specs, bounds: " + backgroundBounds);
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

	private static final Logger LOG = LoggerFactory.getLogger(BackgroundComputationClient.class);
}
