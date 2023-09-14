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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StackAlignmentComparison {

	private static final String baseDataUrl = "http://em-services-1.int.janelia.org:8080/render-ws/v1";
	private static final String owner = "cellmap";
	private static final String project = "jrc_mus_thymus_1";
	private static final String stack1 = "v2_acquire_align";
	private static final String stack2 = "v2_acquire_align";

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
		// data client (and z values) should be the same for both stacks
		final RenderDataClient renderClient = new RenderDataClient(baseDataUrl, owner, project);
		final List<Double> zValues = renderClient.getStackZValues(stack1);
		final DoubleSummaryStatistics minMax = zValues.stream().collect(Collectors.summarizingDouble(Double::doubleValue));

		final ResolvedTileSpecCollection rtsc1 = renderClient.getResolvedTilesForZRange(stack1, minMax.getMin(), minMax.getMax());
		final ResolvedTileSpecCollection rtsc2 = renderClient.getResolvedTilesForZRange(stack2, minMax.getMin(), minMax.getMax());

		final List<CanvasMatches> canvasMatches = getMatchData(rtsc1, renderClient);
		final AlignmentErrors errors1 = computeSolveItemErrors(rtsc1, canvasMatches);
		final AlignmentErrors errors2 = computeSolveItemErrors(rtsc2, canvasMatches);

		final List<Pair<String, String>> pairs = AlignmentErrors.getWorstPairs(errors1, errors2, 10);
		for (final Pair<String, String> pair : pairs)
			System.out.println(pair.getA() + " " + pair.getB() + " : " + errors1.getPairwiseError(pair.getA(), pair.getB()));
	}

	protected static List<CanvasMatches> getMatchData(final ResolvedTileSpecCollection rtsc, final RenderDataClient matchDataClient) throws IOException {

		final Collection<String> sectionIds = rtsc.getTileSpecs().stream().map(TileSpec::getSectionId).distinct().collect(Collectors.toList());
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

		for (final CanvasMatches match : canvasMatches) {
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = rtsc.getTileSpec(pTileId);
			final TileSpec qTileSpec = rtsc.getTileSpec(qTileId);

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

		public static List<Pair<String, String>> getWorstPairs(final AlignmentErrors errors1, final AlignmentErrors errors2, int n) {
			final HashMap<Pair<String, String>, Double> pairToErrorDifference = new HashMap<>();
			errors1.pairToErrorMap.forEach((pair, error1) -> {
				final double error2 = errors2.getPairwiseError(pair.getA(), pair.getB());
				pairToErrorDifference.put(pair, Math.abs(error1 - error2));
			});
			final List<Map.Entry<Pair<String, String>, Double>> entries = new ArrayList<>(pairToErrorDifference.entrySet());
			entries.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
			return entries.stream().map(Map.Entry::getKey).limit(n).collect(Collectors.toList());
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparison.class);
}
