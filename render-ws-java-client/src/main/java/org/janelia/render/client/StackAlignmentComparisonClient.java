package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.OrderedCanvasIdPairWithValue;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

public class StackAlignmentComparisonClient {

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final MatchCollectionParameters matchParams = new MatchCollectionParameters();
		@Parameter(names = "--baselineStack", description = "Stack to use as baseline", required = true)
		private String baselineStack;
		@Parameter(names = "--otherStack", description = "Stack to compare to baseline", required = true)
		private String otherStack;
		@Parameter(names = "--differenceMetric", description = "Metric to use for comparing errors (default: RELATIVE)")
		private DifferenceMetric differenceMetric = DifferenceMetric.RELATIVE;
		@Parameter(names = "--fileName", description = "Name of file to write pairwise errors to (default: pairwiseErrors.json)")
		private String fileName = "pairwiseErrors.json";

		public Parameters() {
		}
	}


	private static final List<Double> blockOptimizerLambdasRigid = Arrays.asList(1.0, 1.0, 0.9, 0.3, 0.01);
	private static final List<Double> blockOptimizerLambdasTranslation = Arrays.asList(1.0, 0.0, 0.0, 0.0, 0.0);
	private static final AffineModel2D stitchingModel = new InterpolatedAffineModel2D<>(
			new InterpolatedAffineModel2D<>(
					new InterpolatedAffineModel2D<>(
							new AffineModel2D(),
							new RigidModel2D(), blockOptimizerLambdasRigid.get(0)),
					new TranslationModel2D(), blockOptimizerLambdasTranslation.get(0)),
			new StabilizingAffineModel2D<>(new RigidModel2D()), 0.0).createAffineModel2D();

	private final Parameters params;

	public StackAlignmentComparisonClient(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) throws Exception {
		final String[] nonEmptyArgs = (args.length != 0) ? args :
				new String[]{
						"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
						"--owner", "hess_wafer_53",
						"--project", "cut_000_to_009",
						"--matchCollection", "c000_s095_v01_match_agg2",
						"--baselineStack", "c000_s095_v01_align2",
						"--otherStack", "c000_s095_v01_align_test_xy_ad",
						"--differenceMetric", "RELATIVE",
						"--fileName", "pairwiseErrorDifferences.json"
				};

		final ClientRunner clientRunner = new ClientRunner(nonEmptyArgs) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final StackAlignmentComparisonClient client = new StackAlignmentComparisonClient(parameters);
				client.fetchAndCompareStacks();
			}
		};
		clientRunner.run();
	}

	public void fetchAndCompareStacks() throws IOException {

		// data clients (and z values) should be the same for both stacks
		final RenderDataClient renderClient = params.renderParams.getDataClient();

		final List<Double> zValues = renderClient.getStackZValues(params.baselineStack);
		final AlignmentErrors errorsBaseline = new AlignmentErrors();
		final AlignmentErrors errorsOther = new AlignmentErrors();

		for (final Double z : zValues) {
			final Bounds layerBounds = renderClient.getLayerBounds(params.baselineStack, z);

			final ResolvedTileSpecsWithMatchPairs baseline = renderClient.getResolvedTilesWithMatchPairs(params.baselineStack, layerBounds, params.matchParams.matchCollection, null, null, false);
			errorsBaseline.absorb(computeSolveItemErrors(baseline, z));

			final ResolvedTileSpecsWithMatchPairs other = renderClient.getResolvedTilesWithMatchPairs(params.otherStack, layerBounds, params.matchParams.matchCollection, null, null, false);
			errorsOther.absorb(computeSolveItemErrors(other, z));
		}

		final AlignmentErrors differences = AlignmentErrors.computeDifferences(errorsBaseline, errorsOther, params.differenceMetric.metricFunction);
		AlignmentErrors.writeToFile(differences, params.fileName);

		LOG.info("Worst pairs:");
		int n = 0;
		for (final OrderedCanvasIdPairWithValue pairWithError : differences.getWorstPairs(50)) {
			n++;
			LOG.info("{}: {}-{} : {}", n, pairWithError.getP(), pairWithError.getQ(), pairWithError.getValue());
		}
	}

	private static AlignmentErrors computeSolveItemErrors(final ResolvedTileSpecsWithMatchPairs tilesAndMatches, final Double currentZ) {
		LOG.info("Computing per-block errors for {} tiles using {} pairs of images ...",
				 tilesAndMatches.getResolvedTileSpecs().getTileCount(), tilesAndMatches.getMatchPairCount());

		// for local fits
		final Model<?> crossLayerModel = new InterpolatedAffineModel2D<>(new AffineModel2D(), new RigidModel2D(), 0.25);
		final AlignmentErrors alignmentErrors = new AlignmentErrors();

		for (final CanvasMatches match : tilesAndMatches.getMatchPairs()) {
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = tilesAndMatches.getTileSpec(pTileId);
			final TileSpec qTileSpec = tilesAndMatches.getTileSpec(qTileId);

			// tile specs can be missing, e.g., due to re-acquisition
			if (pTileSpec == null || qTileSpec == null)
				continue;

			// make sure to record every pair only once by only considering "forward" matches in z
			if ((pTileSpec.getZ() < currentZ) || (qTileSpec.getZ() < currentZ))
				continue;

			final double vDiff = WorkerTools.computeAlignmentError(
					crossLayerModel,
					stitchingModel,
					pTileSpec,
					qTileSpec,
					pTileSpec.getLastTransform().getNewInstance(),
					qTileSpec.getLastTransform().getNewInstance(),
					match.getMatches());

			final OrderedCanvasIdPair pair = match.toOrderedPair();
			alignmentErrors.addError(pair, vDiff);
		}

		LOG.info("computeSolveItemErrors, exit");
		return alignmentErrors;
	}


	public enum DifferenceMetric {
		RELATIVE((a, b) -> Math.abs(a - b) / a),
		ABSOLUTE((a, b) -> Math.abs(a - b));

		public final DoubleBinaryOperator metricFunction;

		DifferenceMetric(final DoubleBinaryOperator metricFunction) {
			this.metricFunction = metricFunction;
		}
	}

	private static class AlignmentErrors {
		private final List<OrderedCanvasIdPairWithValue> pairwiseErrors = new ArrayList<>();

		public void addError(final OrderedCanvasIdPair pair, final double error) {
			pairwiseErrors.add(new OrderedCanvasIdPairWithValue(pair, error));
		}

		public void absorb(final AlignmentErrors other) {
			pairwiseErrors.addAll(other.pairwiseErrors);
		}

		public List<OrderedCanvasIdPairWithValue> getWorstPairs(final int n) {
			return pairwiseErrors.stream()
					.sorted((p1, p2) -> Double.compare(p2.getValue(), p1.getValue()))
					.limit(n)
					.collect(Collectors.toList());
		}

		public static AlignmentErrors computeDifferences(final AlignmentErrors baseline, final AlignmentErrors other, final DoubleBinaryOperator comparisonMetric) {
			final AlignmentErrors differences = new AlignmentErrors();
			final Map<OrderedCanvasIdPair, Double> errorLookup = other.pairwiseErrors.stream()
					.collect(Collectors.toMap(OrderedCanvasIdPairWithValue::getPair, OrderedCanvasIdPairWithValue::getValue));

			baseline.pairwiseErrors.forEach(pairWithValue -> {
				final double otherError = errorLookup.get(pairWithValue.getPair());
				final double errorDifference = comparisonMetric.applyAsDouble(pairWithValue.getValue(), otherError);
				differences.addError(pairWithValue.getPair(), errorDifference);
			});

			return differences;
		}

		public static void writeToFile(final AlignmentErrors errors, final String filename) throws IOException {
			FileUtil.saveJsonFile(filename, errors.pairwiseErrors);
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparisonClient.class);
}
