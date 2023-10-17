package org.janelia.render.client.newsolver;

import bdv.util.BdvStackSource;
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
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.MinimalTileSpecWrapper;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.MinimalTileSpec;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.janelia.render.client.solver.visualize.VisualizeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static org.janelia.render.client.newsolver.AlignmentErrors.MergingMethod;
import static org.janelia.render.client.newsolver.AlignmentErrors.MergingMethod.RELATIVE_DIFFERENCE;

public class StackAlignmentComparisonClient {

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@Parameter(names = "--stack", description = "Stack to use as baseline", required = true)
		private String baselineStack;
		@Parameter(names = "--fileName", description = "Name of file to read pairwise errors from", required = true)
		private String fileName;
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
				client.fetchAndCompareStacks();
			}
		};
		clientRunner.run();
	}

	public void fetchAndCompareStacks() throws IOException {

		// data clients (and z values) should be the same for both stacks
		final RenderDataClient renderClient = params.renderParams.getDataClient();
		final Bounds baselineBounds = renderClient.getStackMetaData(params.baselineStack).getStats().getStackBounds();
		final Bounds otherBounds = renderClient.getStackMetaData(params.otherStack).getStats().getStackBounds();
		final List<Double> zValues = renderClient.getStackZValues(params.baselineStack);
		final AlignmentErrors differences = new AlignmentErrors();

		for (final Double z : zValues) {
			final ResolvedTileSpecsWithMatchPairs baseline = getResolvedTilesWithMatchPairsForZ(renderClient, params.baselineStack, baselineBounds, z);
			baseline.normalize();
			final AlignmentErrors errorsBaseline = computeSolveItemErrors(baseline, z);

			final ResolvedTileSpecsWithMatchPairs other = getResolvedTilesWithMatchPairsForZ(renderClient, params.otherStack, otherBounds, z);
			other.normalize();
			final AlignmentErrors errorsOther = computeSolveItemErrors(other, z);

			differences.absorb(AlignmentErrors.merge(errorsBaseline, errorsOther, params.differenceMetric));
		}

		final List<String> tileIds = tiles.getResolvedTileSpecs().getTileSpecs().stream().map(TileSpec::getTileId).collect(Collectors.toList());
		BdvStackSource<?> source = null;
		final String name = "errors";
		final HashMap<String, AffineModel2D> idToModels = new HashMap<>(tileIds.stream().collect(Collectors.toMap(id -> id, id -> new AffineModel2D())));
		final HashMap<String, MinimalTileSpec> idToTileSpec = new HashMap<>(tileIds.stream().collect(Collectors.toMap(id -> id, id -> new MinimalTileSpecWrapper(tiles.getTileSpec(id)))));
		final HashMap<String, Float> idToValue = new HashMap<>(errors.accumulateForTiles());
		final int minDS = 0;
		final int maxDS = 1;
		final int dsInc = 1;
		final int numThreads = 8;

		VisualizeTools.visualizeMultiRes(
				source,
				name,
				idToModels,
				idToTileSpec,
				idToValue,
				minDS,
				maxDS,
				dsInc,
				numThreads);


		AlignmentErrors.writeToFile(differences, params.fileName);

		LOG.info("Worst pairs:");
		int n = 0;
		for (final OrderedCanvasIdPairWithValue pairWithError : differences.getWorstPairs(50)) {
			n++;
			LOG.info("{}: {}-{} : {}", n, pairWithError.getP(), pairWithError.getQ(), pairWithError.getValue());
		}
	}

	private ResolvedTileSpecsWithMatchPairs getResolvedTilesWithMatchPairsForZ(
			final RenderDataClient renderClient,
			final String stackName,
			final Bounds stackBounds,
			final Double z) throws IOException {
		return renderClient.getResolvedTilesWithMatchPairs(stackName, stackBounds.withZ(z), params.matchParams.matchCollection, null, null, false);
	}

	private static AlignmentErrors computeSolveItemErrors(final ResolvedTileSpecsWithMatchPairs tilesAndMatches, final Double currentZ) {
		LOG.info("Computing errors for {} tiles using {} pairs of images centered in z-layer {}...",
				 tilesAndMatches.getResolvedTileSpecs().getTileCount(), tilesAndMatches.getMatchPairCount(), currentZ);

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

			// make sure to record every cross-layer pair only once by only considering "forward" matches in z
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

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentComparisonClient.class);
}
