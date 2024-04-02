package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import mpicbg.models.RigidModel2D;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.newsolver.solvers.affine.MultiSemPreAligner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.solver.SolveTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Coarsely aligns a multi-SEM stack by treating each mFOV layer as a single tile and using a rigid model.
 *
 * @author Michael Innerberger
 */
public class MultiSemPreAlignClient implements Serializable {

	public static class Parameters extends CommandLineParameters {

		@ParametersDelegate
		public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

		@ParametersDelegate
		public MatchCollectionParameters matches = new MatchCollectionParameters();

		@Parameter(
				names = "--stack",
				description = "Name of source stack",
				required = true)
		public String stack;

		@Parameter(
				names = "--targetStack",
				description = "Name of target stack",
				required = true)
		public String targetStack;

		@Parameter(
				names = "--completeTargetStack",
				description = "Complete the target stack after pre-alignment",
				arity = 0)
		public boolean completeTargetStack = false;

		@Parameter(
				names = "--maxAllowedError",
				description = "Max allowed error (default:10.0)"
		)
		public Double maxAllowedError = 10.0;

		@Parameter(
				names = "--maxIterations",
				description = "Max iterations (default:1000)"
		)
		public Integer maxIterations = 1000;

		@Parameter(
				names = "--maxPlateauWidth",
				description = "Max plateau width (default:250)"
		)
		public Integer maxPlateauWidth = 250;

		@Parameter(
				names = "--maxNumMatches",
				description = "Max number of matches between mFOV layers (default:1000)"
		)
		public Integer maxNumMatches = 1000;

		@Parameter(
				names = "--numThreads",
				description = "Number of threads (default:8)"
		)
		public Integer numThreads = 8;
	}

	public static void main(final String[] args) {
		final ClientRunner clientRunner = new ClientRunner(args) {
			@Override
			public void runClient(final String[] args) throws Exception {

				final Parameters parameters = new Parameters();
				parameters.parse(args);

				LOG.info("runClient: entry, parameters={}", parameters);

				final MultiSemPreAlignClient client = new MultiSemPreAlignClient(parameters);

				client.process();
			}
		};
		clientRunner.run();
	}


	private final Parameters parameters;
	private final RenderDataClient dataClient;

	public MultiSemPreAlignClient(final Parameters parameters) {
		this.parameters = parameters;
		this.dataClient = parameters.renderWeb.getDataClient();
	}

	private void setUpTargetStack() throws IOException {
		final StackMetaData stackMetaData = dataClient.getStackMetaData(parameters.stack);
		dataClient.setupDerivedStack(stackMetaData, parameters.targetStack);
	}

	private void completeTargetStack() throws IOException {
		dataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
	}

	private void process() throws IOException, ExecutionException, InterruptedException {
		setUpTargetStack();

		final ResolvedTileSpecsWithMatchPairs tileSpecsWithMatchPairs =
				dataClient.getResolvedTilesWithMatchPairs(parameters.stack,
														  null,
														  parameters.matches.matchCollection,
														  null,
														  null,
														  true);

		final ResolvedTileSpecCollection rtsc = tileSpecsWithMatchPairs.getResolvedTileSpecs();
		final List<CanvasMatches> matches = tileSpecsWithMatchPairs.getMatchPairs();

		final MultiSemPreAligner<RigidModel2D> preAligner = new MultiSemPreAligner<>(
				new RigidModel2D(),
				parameters.maxAllowedError,
				parameters.maxIterations,
				parameters.maxPlateauWidth,
				parameters.numThreads,
				parameters.maxNumMatches
		);

		final Map<String, RigidModel2D> tileIdToModel = preAligner.preAlign(rtsc, matches);

		for (final Map.Entry<String, RigidModel2D> entry : tileIdToModel.entrySet()) {
			final String tileId = entry.getKey();
			final TransformSpec transformSpec = SolveTools.getTransformSpec(entry.getValue());
			rtsc.addTransformSpecToTile(tileId, transformSpec, TransformApplicationMethod.PRE_CONCATENATE_LAST);
		}

		dataClient.saveResolvedTiles(rtsc, parameters.targetStack, null );
		if (parameters.completeTargetStack) {
			completeTargetStack();
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(MultiSemPreAlignClient.class);
}
