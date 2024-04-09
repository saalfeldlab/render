package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.regex.Pattern;

/**
 * This client reorders the tiles in a multi-sem stack in a way that the rendering order
 * causes overlapping tiles to be rendered in the correct order. (I.e., the tile where
 * the overlapping region is imaged first is on top.)
 *
 * @author Michael Innerberger
 */
public class TileReorderingClient {

	private static final int MFOVS_PER_STACK = 19;
	private static final int SFOVS_PER_MFOV = 91;
	private static final int SFOV_INDEX = 2;
	private static final int MFOV_INDEX = 1;
	private static final Pattern TILE_ID_SEPARATOR = Pattern.compile("_");

	// The new order of the tiles in the multi-sem stack:
	// Number the tiles in an SFOV from 1 to 91, starting in the top left corner and going
	// row by row. Then, record these numbers in the original order of the tiles (i.e.,
	// starting in the middle of the SFOV and spiraling outwards counterclockwise).
	private static final int[] newNumber = {
			46, 47, 36, 35, 45, 56, 57, 48, 37, 27,
			26, 25, 34, 44, 55, 65, 66, 67, 58, 49,
			38, 28, 19, 18, 17, 16, 24, 33, 43, 54,
			64, 73, 74, 75, 76, 68, 59, 50, 39, 29,
			20, 12, 11, 10,  9,  8, 15, 23, 32, 42,
			53, 63, 72, 80, 81, 82, 83, 84, 77, 69,
			60, 51, 40, 30, 21, 13,  6,  5,  4,  3,
			2,  1,  7, 14, 22, 31, 41, 52, 62, 71,
			79, 86, 87, 88, 89, 90, 91, 85, 78, 70, 61
	};


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

		@Parameter(
				names = "--renderingOrder",
				description = "Rendering order",
				required = false)
		public RenderingOrder renderingOrder = RenderingOrder.HORIZONTAL_SCAN;
	}

	public static void main(final String[] args) {
		final String[] xargs = {
				"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
				"--owner", "hess_wafer_53b",
				"--project", "cut_070_to_079",
				"--stack", "c070_s032_v01_align_ic",
				"--targetStack", "c070_s032_v01_mi_reordering_test_scan_order"
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

		for (final String tileId : sourceCollection.getTileIds()) {
			final TileSpec tileSpec = sourceCollection.getTileSpec(tileId);
			tileSpec.setTileId(parameters.renderingOrder.serialNumberFor(tileId) + "_" + tileId);
		}

		if (sourceCollection.getTileCount() > 0) {
			dataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);
		}
	}


	public enum RenderingOrder {
		// mFOVs by number, sFOVs by number (= spiraling outwards)
		ORIGINAL((mFov, sFov) -> sFov + (mFov - 1) * SFOVS_PER_MFOV),
		// mFOVs by reverse number, sFOVs linearly indexed from left to right, top to bottom (= the "correct" order)
		HORIZONTAL_SCAN((mFov, sFov) -> (MFOVS_PER_STACK - mFov) * SFOVS_PER_MFOV + newNumber[sFov - 1]),
		// mFOVs by number, sFOVs linearly indexed from right to left, bottom to top (= the reverse of the "correct" order)
		REVERSE_SCAN((mFov, sFov) -> (mFov - 1) * SFOVS_PER_MFOV + (SFOVS_PER_MFOV - newNumber[sFov - 1]));

		private final IntBinaryOperator mAndSFovToSerialNumber;


		RenderingOrder(final IntBinaryOperator mAndSFovToSerialNumber) {
			this.mAndSFovToSerialNumber = mAndSFovToSerialNumber;
		}

		public String serialNumberFor(final String tileId) {
			final String[] tileIdComponents = TILE_ID_SEPARATOR.split(tileId);
			final int mFov = Integer.parseInt(tileIdComponents[1]);
			final int sFov = Integer.parseInt(tileIdComponents[2]);
			return String.format("%04d", mAndSFovToSerialNumber.applyAsInt(mFov, sFov));
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(TileReorderingClient.class);
}
