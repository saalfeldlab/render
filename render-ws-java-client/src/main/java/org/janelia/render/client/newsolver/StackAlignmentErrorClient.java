package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ResidualCalculator;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.solvers.WorkerTools;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.StabilizingAffineModel2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
		@Parameter(
				names = "--calculateResiduals",
				description = "Calculate root mean squared error for each pair of tiles (default: calculate Preibisch error)",
				arity=0)
		private boolean calculateResiduals;

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

	public void fetchAndComputeError() throws IOException, NoninvertibleModelException {

		final RenderDataClient renderClient = params.renderParams.getDataClient();
		final StackMetaData stackMetaData = renderClient.getStackMetaData(params.stack);
		final StackId stackId = stackMetaData.getStackId();
		final Bounds stackBounds = stackMetaData.getStats().getStackBounds();
		final List<Double> zValues = renderClient.getStackZValues(params.stack);
		final MatchCollectionId matchCollectionId = params.matchParams.getMatchCollectionId(stackId.getOwner());

		final AlignmentErrors errors = new AlignmentErrors();
		for (final Double z : zValues) {
			final ResolvedTileSpecsWithMatchPairs tiles = getResolvedTilesWithMatchPairsForZ(renderClient,
																							 params.stack,
																							 stackBounds,
																							 z);
			tiles.normalize();
			final AlignmentErrors errorsForZ = computeSolveItemErrors(stackId,
																	  matchCollectionId,
																	  tiles,
																	  z,
																	  params.calculateResiduals);
			errors.absorb(errorsForZ);
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

	private static AlignmentErrors computeSolveItemErrors(final StackId stackId,
														  final MatchCollectionId matchCollectionId,
														  final ResolvedTileSpecsWithMatchPairs tilesAndMatches,
														  final Double currentZ,
														  final boolean calculateResiduals)
			throws NoninvertibleModelException {

		LOG.info("computeSolveItemErrors: entry, processing {} tiles ({} pairs) in z {}, calculateResiduals={}",
				 tilesAndMatches.getResolvedTileSpecs().getTileCount(),
				 tilesAndMatches.getMatchPairCount(),
				 currentZ,
				 calculateResiduals);

		// for local fits
		final Model<?> crossLayerModel = new InterpolatedAffineModel2D<>(new AffineModel2D(), new RigidModel2D(), 0.25);
		final AlignmentErrors alignmentErrors = new AlignmentErrors();
		final Map<String, TileSpec> tileIdToMatchTileSpec = new HashMap<>();

		for (final CanvasMatches match : tilesAndMatches.getMatchPairs()) {
			final String pTileId = match.getpId();
			final String qTileId = match.getqId();

			final TileSpec pTileSpec = tilesAndMatches.getTileSpec(pTileId);
			final TileSpec qTileSpec = tilesAndMatches.getTileSpec(qTileId);

			// tile specs can be missing, e.g., due to re-acquisition
			if (pTileSpec == null || qTileSpec == null)
				continue;

			final double errorValue;

			if (calculateResiduals) {
				errorValue = deriveRootMeanSquaredError(stackId,
														matchCollectionId,
														match,
														pTileSpec,
														qTileSpec,
														tileIdToMatchTileSpec);
			} else {
				errorValue = WorkerTools.computeAlignmentError(crossLayerModel,
															   stitchingModel,
															   pTileSpec,
															   qTileSpec,
															   pTileSpec.getLastTransform().getNewInstance(),
															   qTileSpec.getLastTransform().getNewInstance(),
															   match.getMatches());
			}

			final OrderedCanvasIdPair pair = match.toOrderedPair();
			alignmentErrors.addError(pair, errorValue);
		}

		LOG.info("computeSolveItemErrors, exit");

		return alignmentErrors;
	}

	private static double deriveRootMeanSquaredError(final StackId stackId,
													 final MatchCollectionId matchCollectionId,
													 final CanvasMatches match,
													 final TileSpec pAlignedTileSpec,
													 final TileSpec qAlignedTileSpec,
													 final Map<String, TileSpec> tileIdToMatchTileSpec)
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

		final TileSpec pMatchTileSpec = getMatchTileSpec(pAlignedTileSpec, tileIdToMatchTileSpec);
		final TileSpec qMatchTileSpec = getMatchTileSpec(qAlignedTileSpec, tileIdToMatchTileSpec);
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

	private static TileSpec getMatchTileSpec(final TileSpec alignedTileSpec,
											 final Map<String, TileSpec> tileIdToMatchTileSpec) {

		TileSpec matchTileSpec = tileIdToMatchTileSpec.get(alignedTileSpec.getTileId());

		if (matchTileSpec == null) {
			matchTileSpec = alignedTileSpec.slowClone();

			matchTileSpec.flattenTransforms();
			// Assume the last transform is an affine that positions the tile in the world and remove it.
			matchTileSpec.removeLastTransformSpec();
			// If the tile still has more than 2 transforms, remove all but the first 2.
			// This assumes that the first 2 transforms are for lens correction.
			while (matchTileSpec.getTransforms().size() > 2) {
				matchTileSpec.removeLastTransformSpec();
			}

			tileIdToMatchTileSpec.put(matchTileSpec.getTileId(), matchTileSpec);
		}

		return matchTileSpec;
	}

	private static final Logger LOG = LoggerFactory.getLogger(StackAlignmentErrorClient.class);
}
