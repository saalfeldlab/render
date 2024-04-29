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

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This client reorders the tiles in a multi-sem stack in a way that the rendering order
 * causes overlapping tiles to be rendered in different orders. (I.e., the tile where
 * the overlapping region is imaged first is on top.)
 * <br/>
 * Reordering is done by changing tile IDs.  This is sufficient to change the rendering
 * order for a "final export", but changing tile IDs decouples tiles from their
 * match data.  This means that reordered tiles cannot subsequently be used in
 * tasks/pipelines that rely upon match data.
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

		@Parameter(
				names = "--renderingOrder",
				description = "Rendering order")
		public RenderingOrder renderingOrder = RenderingOrder.HORIZONTAL_SCAN;
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final TileReorderingClient client = new TileReorderingClient(parameters);

				client.setUpTargetStack();
				client.reorderTileSpecs();
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

	private void reorderTileSpecs() throws Exception {
		final List<Double> zValues = dataClient.getStackZValues(parameters.stack);
		for (final Double z : zValues) {
			reorderLayer(z);
		}
	}

	private void reorderLayer(final Double z) throws Exception {
		final ResolvedTileSpecCollection sourceCollection = dataClient.getResolvedTiles(parameters.stack, z);
		LOG.info("transferLayer: transferring layer {} with {} tiles", z, sourceCollection.getTileCount());

		final List<TileSpec> orderedTileSpecs = sourceCollection.getTileSpecs().stream()
				.sorted(parameters.renderingOrder)
				.collect(Collectors.toList());

		for (int i = 0; i < orderedTileSpecs.size(); i++) {
			final TileSpec tileSpec = orderedTileSpecs.get(i);
			tileSpec.setTileId(String.format("%4d_%s", i, tileSpec.getTileId()));
		}

		if (sourceCollection.getTileCount() > 0) {
			dataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);
		}
	}


	public enum RenderingOrder implements Comparator<TileSpec> {
		// mFOVs by number, sFOVs by number; this is the original order used to name tile specs (spiraling outwards)
		ORIGINAL((ts1, ts2) -> String.CASE_INSENSITIVE_ORDER.compare(ts1.getTileId(), ts2.getTileId())),

		// mFOVs by reverse number, sFOVs linearly indexed from left to right, top to bottom (= the "correct" order)
		HORIZONTAL_SCAN((ts1, ts2) -> {
			final int mFovOrder = Double.compare(getMFov(ts1), getMFov(ts2));
			if (mFovOrder != 0) {
				return reverse(mFovOrder);
			} else {
				return Double.compare(linearIndex(getSFov(ts1)), linearIndex(getSFov(ts2)));
			}
		}),

		// mFOVs by number, sFOVs linearly indexed from right to left, bottom to top (= the reverse of the "correct" order)
		REVERSE_SCAN(HORIZONTAL_SCAN.reversed()),

		// mFOVs by number, sFOVs by the y-coordinate of the upper edge midpoint
		BY_Y_COORDINATE((ts1, ts2) -> {
			final int mFovOrder = Double.compare(getMFov(ts1), getMFov(ts2));
			if (mFovOrder != 0) {
				return reverse(mFovOrder);
			}

			final double[] midpoint1 = getUpperEdgeMidpoint(ts1);
			final double[] midpoint2 = getUpperEdgeMidpoint(ts2);
			return Double.compare(midpoint1[1], midpoint2[1]);
		});


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

		private static final Pattern TILE_ID_SEPARATOR = Pattern.compile("_");

		private final Comparator<TileSpec> tileSpecComparator;


		RenderingOrder(final Comparator<TileSpec> tileSpecComparator) {
			this.tileSpecComparator = tileSpecComparator;
		}

		@Override
		public int compare(final TileSpec ts1, final TileSpec ts2) {
			return tileSpecComparator.compare(ts1, ts2);
		}

		private static int getMFov(final TileSpec ts) {
			return getConstituent(ts, 1);
		}

		private static int getSFov(final TileSpec ts) {
			return getConstituent(ts, 2);
		}

		private static int getConstituent(final TileSpec ts, final int index) {
			final String tileId = ts.getTileId();
			return Integer.parseInt(TILE_ID_SEPARATOR.split(tileId)[index]);
		}

		private static int reverse(final int order) {
			return - order;
		}

		private static int linearIndex(final int sFov) {
			return newNumber[sFov - 1];
		}

		private static double[] getUpperEdgeMidpoint(final TileSpec tileSpec) {
			final double[][] corners = tileSpec.getRawCornerLocations();
			final double[] topLeft = tileSpec.getTransformList().apply(corners[2]);
			final double[] topRight = tileSpec.getTransformList().apply(corners[3]);
			final double[] rawMidpoint = new double[] { (topLeft[0] + topRight[0]) / 2, (topLeft[1] + topRight[1]) / 2 };
			return tileSpec.getTransformList().apply(rawMidpoint);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(TileReorderingClient.class);
}
