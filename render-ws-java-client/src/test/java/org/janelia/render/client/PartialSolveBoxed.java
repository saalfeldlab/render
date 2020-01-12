package org.janelia.render.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;

public class PartialSolveBoxed< B extends Model< B > & Affine2D< B > > extends PartialSolve< B >
{
	// how many layers on the top and bottom we use as overlap to compute the rigid models that "blend" the re-solved stack back in 
	protected int overlapTop = 25;
	protected int overlapBottom = 25;

	public PartialSolveBoxed(final Parameters parameters) throws IOException
	{
		super( parameters );
	}

	@Override
	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		LOG.info("run: entry");

		LOG.info( "using " + overlapTop + " layers on the top for blending (" + Math.round( minZ ) + "-" + (Math.round( minZ ) + overlapTop -1) + ")" );
		LOG.info( "using " + overlapTop + " layers on the bottom for blending (" + Math.round( maxZ ) + "-" + (Math.round( maxZ ) - overlapTop +1) + ")" );

		final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();
		final HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();
		final HashMap<String, TileSpec> idToTileSpec = new HashMap<>();

		ArrayList< String > log = new ArrayList<>();

		for (final String pGroupId : pGroupList)
		{
			LOG.info("run: connecting tiles with pGroupId {}", pGroupId);

			final List<CanvasMatches> matches = matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = getTileSpec(pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = getTileSpec(qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, parameters.stack);
					continue;
				}

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !idToTileMap.containsKey( pId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = buildTileFromSpec(pTileSpec);
					p = pairP.getA();
					idToTileMap.put( pId, p );
					idToPreviousModel.put( pId, pairP.getB() );
					idToTileSpec.put( pId, pTileSpec );
				}
				else
				{
					p = idToTileMap.get( pId );
				}

				if ( !idToTileMap.containsKey( qId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = buildTileFromSpec(qTileSpec);
					q = pairQ.getA();
					idToTileMap.put( qId, q );
					idToPreviousModel.put( qId, pairQ.getB() );
					idToTileSpec.put( qId, qTileSpec );	
				}
				else
				{
					q = idToTileMap.get( qId );
				}

				p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
			}
		}

		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles(idToTileMap.values());

		LOG.info("run: optimizing {} tiles", idToTileMap.size());

		final List<Double> lambdaValues;

		if (parameters.optimizerLambdas == null)
			lambdaValues = Stream.of(1.0, 0.5, 0.1, 0.01)
					.filter(lambda -> lambda <= parameters.startLambda)
					.collect(Collectors.toList());
		else
			lambdaValues = parameters.optimizerLambdas.stream()
					.sorted(Comparator.reverseOrder())
					.collect(Collectors.toList());

		LOG.info( "lambda's used:" );

		for ( final double lambda : lambdaValues )
			LOG.info( "l=" + lambda );

		for (final double lambda : lambdaValues)
		{
			for (final Tile tile : idToTileMap.values()) {
				((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);
			}

			final int numIterations = lambda < 0.25 ? 200 : parameters.maxIterations;

			// tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);
		
			final ErrorStatistic observer = new ErrorStatistic(parameters.maxPlateauWidth + 1 );
			final float damp = 1.0f;
			TileUtil.optimizeConcurrently(
					observer,
					parameters.maxAllowedError,
					numIterations,
					parameters.maxPlateauWidth,
					damp,
					tileConfig,
					tileConfig.getTiles(),
					tileConfig.getFixedTiles(),
					parameters.numberOfThreads);
		}

		//
		// Compute a smooth rigid transition between the remaining blocks on top and bottom and the re-aligned section
		//
		final Tile<RigidModel2D> topBlock = new Tile<>( new RigidModel2D());
		final Tile<RigidModel2D> reAlignedBlock = new Tile<>( new RigidModel2D());
		final Tile<RigidModel2D> bottomBlock = new Tile<>( new RigidModel2D());


		if (parameters.targetStack == null)
		{
			final HashMap<String, AffineModel2D> idToNewModel = new HashMap<>();

			final ArrayList< String > tileIds = new ArrayList<>( idToTileMap.keySet() );
			Collections.sort( tileIds );

			for (final String tileId : tileIds )
			{
				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);
				final InterpolatedAffineModel2D model = tile.getModel();
				AffineModel2D affine = model.createAffineModel2D();
				idToNewModel.put( tileId, affine );
				LOG.info("tile {} model is {}", tileId, affine);
			}

			new ImageJ();

			// visualize new result
			render( idToNewModel, idToTileSpec, 0.15 );

			// visualize old result
			//render( idToPreviousModel, idToTileSpec, 0.15 );

			SimpleMultiThreading.threadHaltUnClean();
		}
		else
		{
			//saveTargetStackTiles(idToTileMap);
		}


		LOG.info("run: exit");
	}

	public static void main( String[] args )
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_19m",
                            "--project", "Sec10",

                            "--stack", "v2_patch_msolve_fine",
                            //"--targetStack", "null",
                            "--regularizerModelType", "RIGID",
                            "--optimizerLambdas", "1.0",
                            "--minZ", "20500",
                            "--maxZ", "20600",

                            "--threads", "4",
                            "--maxIterations", "10000",
                            "--completeTargetStack",
                            "--matchCollection", "Sec10_patch"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final PartialSolveBoxed client = new PartialSolveBoxed(parameters);

                client.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(PartialSolveBoxed.class);
}
