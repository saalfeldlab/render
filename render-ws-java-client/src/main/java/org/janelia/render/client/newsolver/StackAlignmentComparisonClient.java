package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.NoninvertibleModelException;
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
import org.janelia.render.client.parameter.MatchCollectionParameters;
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
		@ParametersDelegate
		private final MatchCollectionParameters matchParams = new MatchCollectionParameters();
		@Parameter(names = "--stack", description = "Stack for which to compute errors", required = true)
		private String stack;
		@Parameter(
				names = "--errorMetric",
				description = "Error metric to use for computing errors (default: GLOBAL_LOCAL_DIFFERENCE)")
		private StackAlignmentErrorClient.ErrorMetric errorMetric = StackAlignmentErrorClient.ErrorMetric.GLOBAL_LOCAL_DIFFERENCE;
		@Parameter(names = "--compareTo", description = "Stack for which to compare errors to")
		private String baselineStack;
		@Parameter(names = "--comparisonMetric", description = "Metric to use for comparing errors (default: ABSOLUTE_CHANGE)")
		private MergingMethod comparisonMetric = MergingMethod.ABSOLUTE_CHANGE;
		@Parameter(names = "--reportWorstPairs", description = "Report the worst n pairs (default: 20)")
		private int reportWorstPairs = 20;
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
				client.compareAndLogErrors();
			}
		};
		clientRunner.run();
	}

	public void compareAndLogErrors() throws IOException {

		final AlignmentErrors baseline = computeErrorsFor(params.baselineStack);
		final AlignmentErrors other = computeErrorsFor(params.stack);

		final AlignmentErrors differences = AlignmentErrors.merge(baseline, other, params.comparisonMetric);

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
	}

	private AlignmentErrors computeErrorsFor(final String stack){
		final String[] errorArgs = new String[] {
				"--baseDataUrl", params.renderParams.baseDataUrl,
				"--owner", params.renderParams.owner,
				"--project", params.renderParams.project,
				"--matchCollection", params.matchParams.matchCollection,
				"--stack", stack,
				"--errorMetric", params.errorMetric.name()};
		final StackAlignmentErrorClient.Parameters errorParams = new StackAlignmentErrorClient.Parameters();
		errorParams.parse(errorArgs);
		final StackAlignmentErrorClient errorClient = new StackAlignmentErrorClient(errorParams);
		try {
			return errorClient.fetchAndComputeError();
		} catch (final IOException | NoninvertibleModelException e) {
			throw new RuntimeException(e);
		}
	}

	private static String buildProblemAreaNgUrl(final String rendererUrl,
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
