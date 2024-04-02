package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.janelia.alignment.match.OrderedCanvasIdPairWithValue;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.NeuroglancerUtil;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.errors.AlignmentErrors;
import org.janelia.render.client.newsolver.errors.AlignmentErrors.MergingMethod;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class StackAlignmentComparisonClient {

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@Parameter(names = "--stack", description = "Stack for which to compute errors", required = true)
		private String stack;
		@Parameter(names = "--baselineFile", description = "Name of file from which to read pairwise errors as baseline", required = true)
		private String baselineFile;
		@Parameter(names = "--otherFile", description = "Name of file from which to read other pairwise errors", required = true)
		private String otherFile;
		@Parameter(names = "--metric", description = "Metric to use for comparing errors (default: ABSOLUTE_CHANGE)")
		private MergingMethod metric = MergingMethod.ABSOLUTE_CHANGE;
		@Parameter(names = "--outFileName", description = "Name of file to write pairwise error differences to (not written if not specified)")
		private String outFileName = null;
		@Parameter(names = "--reportWorstPairs", description = "Report the worst n pairs (default: 50)")
		private int reportWorstPairs = 50;
	}


	private final Parameters params;

	public StackAlignmentComparisonClient(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) throws Exception {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final StackAlignmentComparisonClient client = new StackAlignmentComparisonClient(parameters);
				client.compareErrors();
			}
		};
		clientRunner.run();
	}

	public void compareErrors() throws IOException {

		final AlignmentErrors baseline = AlignmentErrors.loadFromFile(params.baselineFile);
		final AlignmentErrors other = AlignmentErrors.loadFromFile(params.otherFile);

		final AlignmentErrors differences = AlignmentErrors.merge(baseline, other, params.metric);

		final List<OrderedCanvasIdPairWithValue> worstPairs = differences.getWorstPairs(params.reportWorstPairs);
		final RenderDataClient dataClient = params.renderParams.getDataClient();
		final ResolvedTileSpecCollection rtsc = dataClient.getResolvedTiles(params.stack, null);
		final StackMetaData stackMetaData = dataClient.getStackMetaData(params.stack);
		final String renderUrl = dataClient.getBaseDataUrl().replace("/render-ws/v1", "");

		for (final OrderedCanvasIdPairWithValue pairWithError : worstPairs) {
			final TileSpec p = rtsc.getTileSpec(pairWithError.getP().getId());
			final TileSpec q = rtsc.getTileSpec(pairWithError.getQ().getId());
			final Bounds pairBounds = p.toTileBounds().union(q.toTileBounds());

			final double error = pairWithError.getValue();
			final String layer = (p.getZ().equals(q.getZ())) ? "same" : "cross";
			final String url = buildProblemAreaNgUrl(renderUrl, stackMetaData, pairBounds);
			LOG.info("Error: {} ({} layer)- {}", error, layer, url);
		}

		if (params.outFileName != null) {
			AlignmentErrors.writeToFile(differences, params.outFileName);
		}
	}

	public static String buildProblemAreaNgUrl(final String rendererUrl,
											   final StackMetaData stackMetaData,
											   final Bounds bounds) {
		final List<Double> res = stackMetaData.getCurrentResolutionValues();
		final StackId stackId = stackMetaData.getStackId();
		final Bounds boundsToRender = (bounds != null) ? bounds : stackMetaData.getStats().getStackBounds();

		final String stackDimensions = "\"x\":[" + res.get(0).intValue() + "e-9,\"m\"]," +
									   "\"y\":[" + res.get(1).intValue() + "e-9,\"m\"]," +
									   "\"z\":[" + res.get(2).intValue() + "e-9,\"m\"]";

		final String positionAndScales = NeuroglancerUtil.buildPositionAndScales(boundsToRender, 2, 32768);

		final String ngJson =
				"{\"dimensions\":{" + stackDimensions + "}," + positionAndScales +
						",\"layers\":[{\"type\":\"image\",\"source\":{\"url\":\"render://" +
						rendererUrl + "/" + stackId.getOwner() + "/" + stackId.getProject() + "/" + stackId.getStack() +
						"\",\"subsources\":{\"default\":true,\"bounds\":true},\"enableDefaultSubsources\":false}," +
						"\"tab\":\"source\",\"name\":\"" + stackId.getStack() + "\"}]," +
						"\"selectedLayer\":{\"layer\":\"" + stackId.getStack() + "\"},\"layout\":\"xy\"}";

		return rendererUrl + "/ng/#!" + URLEncoder.encode(ngJson, StandardCharsets.UTF_8);
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparisonClient.class);
}
