package org.janelia.render.client.newsolver;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.CoordinateTransform;
import net.imglib2.RealPoint;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;


/**
 * Client for computing residuals of alignment for a stack.
 */
public class AlignmentResidualClient {

	private ResolvedTileSpecCollection tileSpecs;

	public static class Parameters extends CommandLineParameters {
		@ParametersDelegate
		private final RenderWebServiceParameters renderParams = new RenderWebServiceParameters();
		@ParametersDelegate
		private final MatchCollectionParameters matchParams = new MatchCollectionParameters();
		@Parameter(names = "--stack", description = "Stack for which to compute errors", required = true)
		private String stack;
	}


	private final Parameters params;

	public AlignmentResidualClient(final Parameters params) {
		this.params = params;
	}

	public static void main(final String[] args) throws Exception {
		final String[] testArgs = {
				"--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
				"--owner", "hess_wafer_53",
				"--project", "cut_000_to_009",
				"--stack", "c009_s310_v01_mfov_08_exact",
				"--matchCollection", "c009_s310_v01_match",
				"--matchOwner", "hess_wafer_53"
		};
		final ClientRunner clientRunner = new ClientRunner(testArgs) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);
				LOG.info("runClient: entry, parameters={}", parameters);

				final AlignmentResidualClient client = new AlignmentResidualClient(parameters);
				client.fetchAndComputeResidual();
			}
		};
		clientRunner.run();
	}

	public void fetchAndComputeResidual() throws IOException {

		final RenderDataClient renderClient = params.renderParams.getDataClient();
		final Bounds stackBounds = renderClient.getStackMetaData(params.stack).getStats().getStackBounds();
		final ResolvedTileSpecsWithMatchPairs tiles = renderClient.getResolvedTilesWithMatchPairs(params.stack,
																								  stackBounds,
																								  params.matchParams.matchCollection,
																								  null,
																								  null,
																								  false);
		tiles.normalize();
		tileSpecs = tiles.getResolvedTileSpecs();
		final double totalResidual = tiles.getMatchPairs().stream()
				.mapToDouble(this::computeMatchResidual)
				.sum();
		final int matchCount = tiles.getMatchPairs().stream().mapToInt(CanvasMatches::getMatchCount).sum();
		LOG.info("average residual ({} matches)={}", matchCount, totalResidual / matchCount);
	}

	private double computeMatchResidual(final CanvasMatches match) {
		final String pTileId = match.getpId();
		final String qTileId = match.getqId();

		final TileSpec pTileSpec = tileSpecs.getTileSpec(pTileId);
		final TileSpec qTileSpec = tileSpecs.getTileSpec(qTileId);

		// tile specs can be missing, e.g., due to re-acquisition
		if (pTileSpec == null || qTileSpec == null)
			return 0;

		final CoordinateTransform pTransform = pTileSpec.getLastTransform().getNewInstance();
		final CoordinateTransform qTransform = qTileSpec.getLastTransform().getNewInstance();

		final Matches pointMatches = match.getMatches();
		final List<RealPoint> pPoints = pointMatches.getPList();
		final List<RealPoint> qPoints = pointMatches.getQList();

		if (pPoints.size() != qPoints.size())
			throw new IllegalStateException("pPoints.size() != qPoints.size()");

		final int nPoints = pPoints.size();
		double residual = 0;
		for (int i = 0; i < nPoints; i++) {
			final double[] p = pTransform.apply(pPoints.get(i).positionAsDoubleArray());
			final double[] q = qTransform.apply(qPoints.get(i).positionAsDoubleArray());
			for (int j = 0; j < p.length; j++) {
				final double diff = p[j] - q[j];
				residual += diff * diff;
			}
		}

		return Math.sqrt(residual);
	}

	private static final Logger LOG = LoggerFactory.getLogger(AlignmentResidualClient.class);
}
