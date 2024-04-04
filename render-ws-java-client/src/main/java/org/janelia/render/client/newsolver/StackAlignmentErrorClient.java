package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.OrderedCanvasIdPairWithValue;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.NeuroglancerUtil;
import org.janelia.alignment.util.ResidualCalculator;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.errors.AlignmentErrors;
import org.janelia.render.client.newsolver.errors.AlignmentErrors.MergingMethod;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.SolveTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StackAlignmentErrorClient {

	public enum ErrorMetric {
		GLOBAL_LOCAL_DIFFERENCE,
		RMSE
	}

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final MatchCollectionParameters matchParams = new MatchCollectionParameters();
		@Parameter(names = "--stack", description = "Stack for which to compute errors", required = true)
		private String stack;
		@Parameter(
				names = "--errorMetric",
				description = "Error metric to use for computing errors",
				variableArity = true,
				required = true)
		private List<ErrorMetric> errorMetricList;
		@Parameter(names = "--compareTo", description = "Stack for which to compare errors to")
		private String baselineStack = null;
		@Parameter(names = "--comparisonMetric", description = "Metric to use for comparing errors")
		private MergingMethod comparisonMetric = MergingMethod.ABSOLUTE_CHANGE;
		@Parameter(names = "--reportWorstPairs", description = "Report the worst n pairs")
		private int reportWorstPairs = 20;
	}


	private final Parameters params;


	public StackAlignmentErrorClient(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final StackAlignmentErrorClient client = new StackAlignmentErrorClient(parameters);
				client.compareAndLogErrors();
			}
		};
		clientRunner.run();
	}

	public void compareAndLogErrors() throws IOException {

		final Map<ErrorMetric, AlignmentErrors> metricToErrors;
		if (params.baselineStack != null) {
			// TODO: re-use the same matches for both stacks
			final Map<ErrorMetric, AlignmentErrors> baseline = computeErrorsFor(params.baselineStack);
			final Map<ErrorMetric, AlignmentErrors> other = computeErrorsFor(params.stack);
			metricToErrors = new HashMap<>();
			for (final ErrorMetric metric : baseline.keySet()) {
				metricToErrors.put(metric,
								   AlignmentErrors.merge(baseline.get(metric),
														 other.get(metric),
														 params.comparisonMetric));
			}
		} else {
			metricToErrors = computeErrorsFor(params.stack);
		}

		final RenderDataClient dataClient = params.renderParams.getDataClient();
		final ResolvedTileSpecCollection rtsc = dataClient.getResolvedTiles(params.stack, null);
		final StackMetaData stackMetaData = dataClient.getStackMetaData(params.stack);
		final String renderUrl = dataClient.getBaseDataUrl().replace("/render-ws/v1", "");

		final int numberOfErrorMetrics = metricToErrors.size();
		final Map<ErrorMetric, Map<OrderedCanvasIdPair, Double>> metricToPairMap = new HashMap<>();
		if (numberOfErrorMetrics > 1) {
			for (final ErrorMetric metric : metricToErrors.keySet()) {
				metricToPairMap.put(metric, metricToErrors.get(metric).buildPairToErrorMap());
			}
		}
		final List<ErrorMetric> sortedMetrics = metricToErrors.keySet().stream().sorted().collect(Collectors.toList());

		for (final ErrorMetric metric : metricToErrors.keySet()) {

			final AlignmentErrors errors = metricToErrors.get(metric);

			final Map<String, AlignmentErrors> errorsByPGroupId = errors.splitByPGroupId();
			final List<Double> sortedPZs = errorsByPGroupId.keySet().stream()
					.mapToDouble(Double::parseDouble).sorted().boxed().collect(Collectors.toList());

			for (final Double pZ : sortedPZs) {
				final AlignmentErrors errorsForPZ = errorsByPGroupId.get(pZ.toString());
				final List<OrderedCanvasIdPairWithValue> worstPairs =
						errorsForPZ.getWorstPairs(params.reportWorstPairs);
				for (final OrderedCanvasIdPairWithValue pairWithError : worstPairs) {
					final TileSpec p = rtsc.getTileSpec(pairWithError.getP().getId());
					final TileSpec q = rtsc.getTileSpec(pairWithError.getQ().getId());
					final Bounds pairBounds = p.toTileBounds().union(q.toTileBounds());

					final double error = pairWithError.getValue();
					final String url = buildProblemAreaNgUrl(renderUrl, stackMetaData, pairBounds);

					final StringBuilder otherErrorValues = new StringBuilder();
					if (numberOfErrorMetrics > 1) {
						otherErrorValues.append(", ");
						for (final ErrorMetric otherMetric : sortedMetrics) {
							if (! otherMetric.equals(metric)) {
								final Map<OrderedCanvasIdPair, Double> otherErrors = metricToPairMap.get(otherMetric);
								final double otherError = otherErrors.get(pairWithError.getPair());
								otherErrorValues.append(otherMetric).append("-error: ").append(otherError).append(", ");
							}
						}
						otherErrorValues.delete(otherErrorValues.length() - 2, otherErrorValues.length());
					}

					LOG.info("pZ: {}, pTileId: {}, qZ: {}, qTileId: {}, {}-error: {}{}, ng: {}",
							 p.getZ(), p.getTileId(), q.getZ(), q.getTileId(), metric, error, otherErrorValues, url);
				}
			}
		}
	}

	private Map<ErrorMetric, AlignmentErrors> computeErrorsFor(final String stack){
		try {
			return fetchAndComputeError(stack);
		} catch (final IOException | NoninvertibleModelException e) {
			throw new RuntimeException(e);
		}
	}

	public Map<ErrorMetric, AlignmentErrors> fetchAndComputeError(final String stack) throws IOException, NoninvertibleModelException {

		final RenderDataClient renderClient = params.renderParams.getDataClient();
		final StackMetaData stackMetaData = renderClient.getStackMetaData(stack);
		final StackId stackId = stackMetaData.getStackId();
		final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
		final List<Double> zValues = renderClient.getStackZValues(stack);
		final MatchCollectionId matchCollectionId = params.matchParams.getMatchCollectionId(stackId.getOwner());

		final Map<ErrorMetric, AlignmentErrors> metricToErrors = new HashMap<>();
		params.errorMetricList.forEach(metric -> metricToErrors.put(metric, new AlignmentErrors()));

		for (final Double z : zValues) {
			final ResolvedTileSpecsWithMatchPairs tiles = getResolvedTilesWithMatchPairsForZ(renderClient,
																							 stack,
																							 stackBounds,
																							 z);
			tiles.normalize();
			final Map<ErrorMetric, AlignmentErrors> metricToErrorsForZ = computeSolveItemErrors(stackId,
																								matchCollectionId,
																								tiles,
																								z,
																								params.errorMetricList);
			metricToErrorsForZ.forEach((metric, errorsForZ) -> metricToErrors.get(metric).absorb(errorsForZ));
		}

		return metricToErrors;
	}

	private ResolvedTileSpecsWithMatchPairs getResolvedTilesWithMatchPairsForZ(
			final RenderDataClient renderClient,
			final String stackName,
			final Bounds stackBounds,
			final Double z) throws IOException {
		return renderClient.getResolvedTilesWithMatchPairs(stackName, stackBounds.withZ(z), params.matchParams.matchCollection, null, null, false);
	}

	private static Map<ErrorMetric, AlignmentErrors> computeSolveItemErrors(final StackId stackId,
																			final MatchCollectionId matchCollectionId,
																			final ResolvedTileSpecsWithMatchPairs tilesAndMatches,
																			final Double currentZ,
																			final List<ErrorMetric> errorMetricList)
			throws NoninvertibleModelException {

		LOG.info("computeSolveItemErrors: entry, processing {} tiles ({} pairs) in z {}, errorMetricList={}",
				 tilesAndMatches.getResolvedTileSpecs().getTileCount(),
				 tilesAndMatches.getMatchPairCount(),
				 currentZ,
				 errorMetricList);

		// for local fits
		// final Model<?> crossLayerModel = new InterpolatedAffineModel2D<>(new AffineModel2D(), new RigidModel2D(), 0.25);
		final Model<?> stitchingModel = new InterpolatedAffineModel2D<>(new AffineModel2D(), new RigidModel2D(), 0.01);

		final Map<ErrorMetric, AlignmentErrors> metricToErrors = new HashMap<>();
		errorMetricList.forEach(metric -> metricToErrors.put(metric, new AlignmentErrors()));

		final Map<String, TileSpec> tileIdToMatchTileSpec = new HashMap<>();
		if (errorMetricList.contains(ErrorMetric.RMSE)) {
			for (final TileSpec tileSpec : tilesAndMatches.getResolvedTileSpecs().getTileSpecs()) {
				tileIdToMatchTileSpec.put(tileSpec.getTileId(),
										  buildTileSpecUsedForMatchDerivation(tileSpec));
			}
		}

		for (final CanvasMatches match : tilesAndMatches.getMatchPairs()) {
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = tilesAndMatches.getTileSpec(pTileId);
			final TileSpec qTileSpec = tilesAndMatches.getTileSpec(qTileId);

			// tile specs can be missing, e.g., due to re-acquisition
			if (pTileSpec == null || qTileSpec == null)
				continue;

			final OrderedCanvasIdPair pair = match.toOrderedPair();

			if (errorMetricList.contains(ErrorMetric.RMSE)) {
				final double errorValue = deriveRootMeanSquaredError(stackId,
																	 matchCollectionId,
																	 pTileSpec,
																	 qTileSpec,
																	 match,
																	 tileIdToMatchTileSpec.get(pTileSpec.getTileId()),
																	 tileIdToMatchTileSpec.get(qTileSpec.getTileId()));
				metricToErrors.get(ErrorMetric.RMSE).addError(pair, errorValue);
			}

			if (errorMetricList.contains(ErrorMetric.GLOBAL_LOCAL_DIFFERENCE)) {
				final double errorValue = SolveTools.computeDifferenceToOptimalFit(stitchingModel,
																				   pTileSpec.getLastTransform().getNewInstance(),
																				   qTileSpec.getLastTransform().getNewInstance(),
																				   match.getMatches());
				metricToErrors.get(ErrorMetric.GLOBAL_LOCAL_DIFFERENCE).addError(pair, errorValue);
			}
		}

		LOG.info("computeSolveItemErrors, exit");

		return metricToErrors;
	}

	private static double deriveRootMeanSquaredError(final StackId stackId,
													 final MatchCollectionId matchCollectionId,
													 final TileSpec pAlignedTileSpec,
													 final TileSpec qAlignedTileSpec,
													 final CanvasMatches match,
													 final TileSpec pMatchTileSpec,
													 final TileSpec qMatchTileSpec)
			throws NoninvertibleModelException {

		final String pTileId = pAlignedTileSpec.getTileId();
		final String qTileId = qAlignedTileSpec.getTileId();
		final ResidualCalculator residualCalculator = new ResidualCalculator();
		final ResidualCalculator.InputData inputData = new ResidualCalculator.InputData(pTileId,
																						qTileId,
																						stackId,
																						matchCollectionId,
																						false);
		final List<PointMatch> worldMatchList = match.getMatches().createPointMatches();
		final List<PointMatch> localMatchList = ResidualCalculator.convertMatchesToLocal(worldMatchList,
																						 pMatchTileSpec,
																						 qMatchTileSpec);

		if (localMatchList.isEmpty()) {
			throw new IllegalArgumentException(inputData.getMatchCollectionId() + " has " +
													   worldMatchList.size() + " matches between " + pTileId + " and " +
													   qTileId + " but none of them are invertible");
		}

		final ResidualCalculator.Result result = residualCalculator.run(stackId,
																		inputData,
																		localMatchList,
																		pAlignedTileSpec,
																		qAlignedTileSpec);

		return result.getRootMeanSquareError();
	}

	private static TileSpec buildTileSpecUsedForMatchDerivation(final TileSpec alignedTileSpec) {

		final TileSpec matchTileSpec = alignedTileSpec.slowClone();

		matchTileSpec.flattenTransforms();

		// Assume the last transform is an affine that positions the tile in the world and remove it.
		matchTileSpec.removeLastTransformSpec();
		// If the tile still has more than 2 transforms, remove all but the first 2.
		// This assumes that the first 2 transforms are for lens correction.
		while (matchTileSpec.getTransforms().size() > 2) {
			matchTileSpec.removeLastTransformSpec();
		}

		return matchTileSpec;
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

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentErrorClient.class);
}
