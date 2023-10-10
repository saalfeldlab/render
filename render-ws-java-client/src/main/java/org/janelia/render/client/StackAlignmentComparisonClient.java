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
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

// TODO: make this into a full fledged command line tool
//       * break up computation and save into z-layers (could cause problems for larger stacks)
//       * switch output format to json (see FileUtil.saveJsonFile)
public class StackAlignmentComparisonClient extends CommandLineParameters {

	@ParametersDelegate
	private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
	@ParametersDelegate
	private final MatchCollectionParameters matchParams = new MatchCollectionParameters();
	@Parameter(names = "--baselineStack", description = "Stack to use as baseline", required = true)
	private String baselineStack;
	@Parameter(names = "--otherStack", description = "Stack to compare to baseline", required = true)
	private String otherStack;

	private static final List<Double> blockOptimizerLambdasRigid = Arrays.asList(1.0, 1.0, 0.9, 0.3, 0.01);
	private static final List<Double> blockOptimizerLambdasTranslation = Arrays.asList(1.0, 0.0, 0.0, 0.0, 0.0);
	private static final AffineModel2D stitchingModel = new InterpolatedAffineModel2D<>(
			new InterpolatedAffineModel2D<>(
					new InterpolatedAffineModel2D<>(
							new AffineModel2D(),
							new RigidModel2D(), blockOptimizerLambdasRigid.get(0)),
					new TranslationModel2D(), blockOptimizerLambdasTranslation.get(0)),
			new StabilizingAffineModel2D<>(new RigidModel2D()), 0.0).createAffineModel2D();

	public static void main(final String[] args) throws Exception {
		final StackAlignmentComparisonClient client = new StackAlignmentComparisonClient();
		if (args.length == 0) {
			final String[] testArgs = {
					"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
					"--owner", "hess_wafer_53",
					"--project", "cut_000_to_009",
					"--matchCollection", "c000_s095_v01_match_agg2",
					"--baselineStack", "c000_s095_v01_align2",
					"--otherStack", "c000_s095_v01_align_test_xy_ad"
			};
			client.parse(testArgs);
		} else {
			client.parse(args);
		}

		// data clients (and z values) should be the same for both stacks
		final RenderDataClient renderClient = client.renderParams.getDataClient();
		final RenderDataClient matchClient = client.matchParams.getMatchDataClient(renderClient.getBaseDataUrl(), renderClient.getOwner());

		final ResolvedTileSpecCollection rtscBaseline = renderClient.getResolvedTilesForZRange(client.baselineStack, null, null);
		final ResolvedTileSpecCollection rtscNew = renderClient.getResolvedTilesForZRange(client.otherStack, null, null);
		final List<CanvasMatches> canvasMatches = getMatchData(matchClient, rtscBaseline);

		final AlignmentErrors errorsBaseline = computeSolveItemErrors(rtscBaseline, canvasMatches);
		final AlignmentErrors errorsNew = computeSolveItemErrors(rtscNew, canvasMatches);

		final AlignmentErrors differences = AlignmentErrors.computeRelativeDifferences(errorsBaseline, errorsNew);
		AlignmentErrors.writeAsCsv(differences, "pairwiseErrorDifferences.csv");

		LOG.info("Worst pairs:");
		int n = 0;
		for (final OrderedCanvasIdPairWithValue pairWithError : differences.getWorstPairs(50)) {
			n++;
			LOG.info("{}: {}-{} : {}", n, pairWithError.getP(), pairWithError.getQ(), pairWithError.getValue());
		}
	}

	protected static List<CanvasMatches> getMatchData(final RenderDataClient matchDataClient, final ResolvedTileSpecCollection rtsc) throws IOException {

		final Set<String> sectionIds = rtsc.getTileSpecs().stream().map(TileSpec::getSectionId).collect(Collectors.toSet());
		final List<CanvasMatches> canvasMatches = new ArrayList<>();

		for (final String groupId : sectionIds) {
			final List<CanvasMatches> serviceMatchList = matchDataClient.getMatchesWithPGroupId(groupId, false);
			canvasMatches.addAll(serviceMatchList);
		}

		return canvasMatches;
	}

	// TODO: move this to its own class to make space for persisting the data (json, web service, etc.)
	private static AlignmentErrors computeSolveItemErrors(final ResolvedTileSpecCollection rtsc, final List<CanvasMatches> canvasMatches) {
		LOG.info("Computing per-block errors for {} tiles using {} pairs of images ...", rtsc.getTileCount(), canvasMatches.size());

		// for local fits
		final Model<?> crossLayerModel = new InterpolatedAffineModel2D<>(new AffineModel2D(), new RigidModel2D(), 0.25);
		final AlignmentErrors alignmentErrors = new AlignmentErrors();

		int n = 0;
		final int N = canvasMatches.size();
		for (final CanvasMatches match : canvasMatches) {
			n++;
			LOG.info("Processing match {} / {}", n, N);
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = rtsc.getTileSpec(pTileId);
			final TileSpec qTileSpec = rtsc.getTileSpec(qTileId);

			if (pTileSpec == null || qTileSpec == null)
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

	private static class AlignmentErrors {
		private final List<OrderedCanvasIdPairWithValue> pairwiseErrors = new ArrayList<>();

		public void addError(final OrderedCanvasIdPair pair, final double error) {
			pairwiseErrors.add(new OrderedCanvasIdPairWithValue(pair, error));
		}

		public List<OrderedCanvasIdPairWithValue> getWorstPairs(final int n) {
			return pairwiseErrors.stream()
					.sorted((p1, p2) -> Double.compare(p2.getValue(), p1.getValue()))
					.limit(n)
					.collect(Collectors.toList());
		}

		public static AlignmentErrors computeRelativeDifferences(final AlignmentErrors baseline, final AlignmentErrors other) {
			return computeDifferences(baseline, other, (error1, error2) -> Math.abs(error1 - error2) / error1);
		}

		public static AlignmentErrors computeAbsoluteDifferences(final AlignmentErrors baseline, final AlignmentErrors other) {
			return computeDifferences(baseline, other, (error1, error2) -> Math.abs(error1 - error2));
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

		public static void writeAsCsv(final AlignmentErrors errors, final String filename) throws IOException {
			final File file = new File(filename);
			try (final FileWriter writer = new FileWriter(file)) {
				for (final OrderedCanvasIdPairWithValue pairWithError : errors.pairwiseErrors) {
					final String pTileId = pairWithError.getP().getId();
					final String qTileId = pairWithError.getQ().getId();
					final double error = pairWithError.getValue();
					writer.write(pTileId + "," + qTileId + "," + error + "\n");
				}
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparisonClient.class);
}
