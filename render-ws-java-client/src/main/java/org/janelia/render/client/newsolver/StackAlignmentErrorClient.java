package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class StackAlignmentErrorClient {

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final MatchCollectionParameters matchParams = new MatchCollectionParameters();
		@Parameter(names = "--stack", description = "Stack for which to compute errors", required = true)
		private String stack;
		@Parameter(names = "--fileName", description = "Name of file to write pairwise errors to (default: errors_<stack>.json.gz)")
		private String fileName = null;

		public String getFileName() {
			if (fileName == null) {
				fileName = "errors_" + stack + ".json.gz";
			}
			return fileName;
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

	public StackAlignmentErrorClient(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) throws Exception {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final StackAlignmentErrorClient client = new StackAlignmentErrorClient(parameters);
				client.fetchAndComputeError();
			}
		};
		clientRunner.run();
	}

	public void fetchAndComputeError() throws IOException {

		final RenderDataClient renderClient = params.renderParams.getDataClient();
		final Bounds stackBounds = renderClient.getStackMetaData(params.stack).getStats().getStackBounds();
		final List<Double> zValues = renderClient.getStackZValues(params.stack);

		final AlignmentErrors errors = new AlignmentErrors();
		for (final Double z : zValues) {
			final ResolvedTileSpecsWithMatchPairs tiles = getResolvedTilesWithMatchPairsForZ(renderClient, params.stack, stackBounds, z);
			tiles.normalize();
			errors.absorb(computeSolveItemErrors(tiles, z));
		}

		AlignmentErrors.writeToFile(errors, params.getFileName());
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


	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentErrorClient.class);
}
