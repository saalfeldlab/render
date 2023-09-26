package org.janelia.render.client;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import net.imglib2.util.Pair;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.solver.SerializableValuePair;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.stream.Collectors;

public class StackAlignmentComparison {

	private static final String baseDataUrl = "http://em-services-1.int.janelia.org:8080/render-ws/v1";
	private static final String owner = "hess_wafer_53";
	private static final String project = "cut_000_to_009";
	private static final String matchCollection = "c000_s095_v01_match_agg2";
	private static final String baselineStack = "c000_s095_v01_align2";
	private static final String newStack = "c000_s095_v01_align_test_xy_ad";

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
		// data clients (and z values) should be the same for both stacks
		final RenderDataClient renderClient = new RenderDataClient(baseDataUrl, owner, project);
		final RenderDataClient matchClient = new RenderDataClient(baseDataUrl, owner, matchCollection);

		final ResolvedTileSpecCollection rtscBaseline = renderClient.getResolvedTilesForZRange(baselineStack, null, null);
		final ResolvedTileSpecCollection rtscNew = renderClient.getResolvedTilesForZRange(newStack, null, null);
		final List<CanvasMatches> canvasMatches = getMatchData(matchClient);

		final AlignmentErrors errorsBaseline = computeSolveItemErrors(rtscBaseline, canvasMatches);
		final AlignmentErrors errorsNew = computeSolveItemErrors(rtscNew, canvasMatches);

		final AlignmentErrors differences = AlignmentErrors.computeRelativeDifferences(errorsBaseline, errorsNew);
		AlignmentErrors.writeAsCsv(differences, "pairwiseErrorDifferences.csv");
		for (final Pair<String, String> pair : differences.getWorstPairs(50))
			System.out.println(pair.getA() + " " + pair.getB() + " : " + differences.getPairwiseError(pair.getA(), pair.getB()));
	}

	protected static List<CanvasMatches> getMatchData(final RenderDataClient matchDataClient) throws IOException {

		final Collection<String> sectionIds = matchDataClient.getMatchPGroupIds();
		final List<CanvasMatches> canvasMatches = new ArrayList<>();

		for (final String pGroupId : sectionIds) {
			final List<CanvasMatches> serviceMatchList = matchDataClient.getMatchesWithPGroupId(pGroupId, false);
			canvasMatches.addAll(serviceMatchList);
		}

		return canvasMatches;
	}

	private static AlignmentErrors computeSolveItemErrors(final ResolvedTileSpecCollection rtsc, final List<CanvasMatches> canvasMatches) {
		LOG.info("Computing per-block errors for " + rtsc.getTileCount() + " tiles using " + canvasMatches.size() + " pairs of images ...");

		// for local fits
		final Model<?> crossLayerModel = new InterpolatedAffineModel2D<>(new AffineModel2D(), new RigidModel2D(), 0.25);
		final AlignmentErrors alignmentErrors = new AlignmentErrors();

		int n = 0;
		final int N = canvasMatches.size();
		for (final CanvasMatches match : canvasMatches) {
			LOG.info("Processing match {} / {}", ++n, N);
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


			alignmentErrors.addPairwiseError(pTileId, qTileId, vDiff);
		}

		LOG.info("computeSolveItemErrors, exit");
		return alignmentErrors;
	}

	private static class AlignmentErrors {
		private final Map<Pair<String, String>, Double> pairToErrorMap = new HashMap<>();

		public void addPairwiseError(final String tileId, final String otherTileId, final double error) {
			// store pair only once by sorting the pairs in ascending order
			if (tileId.compareTo(otherTileId) < 0)
				addError(tileId, otherTileId, error);
			else
				addError(otherTileId, tileId, error);
		}

		private void addError(final String tileId, final String otherTileId, final double error) {
			pairToErrorMap.put(new SerializableValuePair<>(tileId, otherTileId), error);
		}

		private double getPairwiseError(final String tileId, final String otherTileId) {
			if (tileId.compareTo(otherTileId) < 0)
				return pairToErrorMap.get(new SerializableValuePair<>(tileId, otherTileId));
			else
				return pairToErrorMap.get(new SerializableValuePair<>(otherTileId, tileId));
		}

		public List<Pair<String, String>> getWorstPairs(final int n) {
			return pairToErrorMap.keySet().stream()
					.sorted((p1, p2) -> Double.compare(pairToErrorMap.get(p2), pairToErrorMap.get(p1)))
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

			baseline.pairToErrorMap.forEach((pair, error1) -> {
				final double error2 = other.pairToErrorMap.get(pair);
				differences.pairToErrorMap.put(pair, comparisonMetric.applyAsDouble(error1, error2));
			});

			return differences;
		}

		public static void writeAsCsv(final AlignmentErrors errors, final String filename) throws IOException {
			final File file = new File(filename);
			try (final FileWriter writer = new FileWriter(file)) {
				for (final Map.Entry<Pair<String, String>, Double> entry : errors.pairToErrorMap.entrySet()) {
					final String pTileId = entry.getKey().getA();
					final String qTileId = entry.getKey().getB();
					final double error = entry.getValue();
					writer.write(pTileId + "," + qTileId + "," + error + "\n");
				}
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparison.class);
}
