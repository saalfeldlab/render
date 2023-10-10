package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
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
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
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
		final List<CanvasMatches> canvasMatches = getMatchData(matchClient);

		final AlignmentErrors errorsBaseline = computeSolveItemErrors(rtscBaseline, canvasMatches);
		final AlignmentErrors errorsNew = computeSolveItemErrors(rtscNew, canvasMatches);

		final AlignmentErrors differences = AlignmentErrors.computeRelativeDifferences(errorsBaseline, errorsNew);
		AlignmentErrors.writeAsCsv(differences, "pairwiseErrorDifferences.csv");

		LOG.info("Worst pairs:");
		int n = 0;
		for (final Pair<String, String> pair : differences.getWorstPairs(50)) {
			n++;
			LOG.info("{}: {}-{} : {}", n, pair.getA(), pair.getB(), differences.getPairwiseError(pair.getA(), pair.getB()));
		}
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

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparisonClient.class);
}
