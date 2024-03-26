package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * This client reorders the tiles in a multi-sem stack in a way that the rendering order
 * causes overlapping tiles to be rendered in the correct order. (I.e., the tile where
 * the overlapping region is imaged first is on top.)
 *
 * @author Michael Innerberger
 */
public class TileReorderingClient {
	public static class Parameters extends CommandLineParameters {

		@ParametersDelegate
		public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

		@Parameter(
				names = "--stack",
				description = "Name of source stack",
				required = true)
		public String stack;

		@Parameter(
				names = "--targetStack",
				description = "Name of target stack",
				required = true)
		public String targetStack;
	}

	public static void main(final String[] args) {
		final String[] xargs = {
				"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
				"--owner", "hess_wafer_53b",
				"--project", "cut_070_to_079",
				"--stack", "c070_s032_v01_align_ic",
				"--targetStack", "c070_s032_v01_mi_reordering_test"
		};
		final ClientRunner clientRunner = new ClientRunner(xargs) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final TileReorderingClient client = new TileReorderingClient(parameters);

				client.setUpTargetStack();
				client.transferTileSpecs();
				client.completeTargetStack();
			}
		};
		clientRunner.run();
	}

	private final Parameters parameters;
	private final RenderDataClient dataClient;

	private TileReorderingClient(final Parameters parameters) {
		this.parameters = parameters;
		this.dataClient = parameters.renderWeb.getDataClient();
	}

	private void setUpTargetStack() throws Exception {
		final StackMetaData sourceStackMetaData = dataClient.getStackMetaData(parameters.stack);
		dataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);
		LOG.info("setUpTargetStack: setup stack {}", parameters.targetStack);
	}

	private void completeTargetStack() throws Exception {
		dataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
		LOG.info("completeTargetStack: setup stack {}", parameters.targetStack);
	}

	private void transferTileSpecs() throws Exception {
		final List<Double> zValues = dataClient.getStackZValues(parameters.stack);
		for (final Double z : zValues) {
			transferLayer(z);
		}
	}

	private void transferLayer(final Double z) throws Exception {
		final ResolvedTileSpecCollection sourceCollection = dataClient.getResolvedTiles(parameters.stack, z);
		LOG.info("transferLayer: transferring layer {} with {} tiles", z, sourceCollection.getTileCount());

		if (sourceCollection.getTileCount() > 0) {
			dataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(TileReorderingClient.class);
}
