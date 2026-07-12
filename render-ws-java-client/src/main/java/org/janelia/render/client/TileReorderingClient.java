package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.alignment.multisem.MultiSemUtilities;
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
			return MultiSemUtilities.getRowMajorSFOVIndex(sFov);
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
