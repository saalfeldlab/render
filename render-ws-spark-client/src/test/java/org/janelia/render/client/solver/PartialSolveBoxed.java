package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.render.client.ClientRunner;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;

public class PartialSolveBoxed< B extends Model< B > & Affine2D< B > >
{
	final Parameters parameters;
	final RunParameters runParams;

	// how many layers on the top and bottom we use as overlap to compute the rigid models that "blend" the re-solved stack back in 
	protected int overlapTop = 25;//50;
	protected int overlapBottom = 25;//50;

	public PartialSolveBoxed(final Parameters parameters) throws IOException
	{
		this.parameters = parameters;
		this.runParams = SolveTools.setupSolve( parameters );
	}

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		LOG.info("run: entry");

		final int topBorder = ((int)Math.round( runParams.minZ ) + overlapTop -1);
		final int bottomBorder = ((int)Math.round( runParams.maxZ ) - overlapBottom +1);

		LOG.info( "using " + overlapTop + " layers on the top for blending (" + Math.round( runParams.minZ ) + "-" + topBorder + ")" );
		LOG.info( "using " + overlapBottom + " layers on the bottom for blending (" + Math.round( runParams.maxZ ) + "-" + bottomBorder + ")" );

		final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();
		final HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();
		final HashMap<String, TileSpec> idToTileSpec = new HashMap<>();

		// one object per Tile, we just later know the new affine model to create all matches
		// just want to avoid to load the data twice
		HashSet< String > topTileIds = new HashSet<>();
		HashSet< String > bottomTileIds = new HashSet<>();

		ArrayList< String > idsToIgnore = new ArrayList<>();
		//idsToIgnore.add( "_0-0-2.22801" );

		final ArrayList< String > connectedTiles = new ArrayList<>();

		HashMap< Integer, Integer > zLimits = new HashMap<>();
		//zLimits.put( 15769, 1 );

		for (final String pGroupId : runParams.pGroupList.stream().map(Pair::getA).collect( Collectors.toList() ) )
		{
			LOG.info("run: connecting tiles with pGroupId {}", pGroupId);

			final List<CanvasMatches> matches = runParams.matchDataClient.getMatchesWithPGroupId(pGroupId, false);

			for (final CanvasMatches match : matches)
			{
				final String pId = match.getpId();
				final TileSpec pTileSpec = SolveTools.getTileSpec(parameters, runParams, pGroupId, pId);

				final String qGroupId = match.getqGroupId();
				final String qId = match.getqId();
				final TileSpec qTileSpec = SolveTools.getTileSpec(parameters, runParams, qGroupId, qId);

				if ((pTileSpec == null) || (qTileSpec == null))
				{
					LOG.info("run: ignoring pair ({}, {}) because one or both tiles are missing from stack {}", pId, qId, parameters.stack);
					continue;
				}

				boolean ignore = false;

				for ( final String toIgnore : idsToIgnore )
					if ( pId.contains( toIgnore ) || qId.contains( toIgnore ) )
						ignore = true;

				if ( ignore )
					continue;

				//if ( pId.contains("_0-0-1.13172") || pId.contains("_0-0-1.13381") || qId.contains("_0-0-1.13172") || qId.contains("_0-0-1.13381") )
				//	continue;

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				if ( zLimits.containsKey( pZ ) )
				{
					final int limit = zLimits.get( pZ );

					if ( Math.abs( qTileSpec.getZ() - pTileSpec.getZ() ) > limit )
					{
						System.out.println( "IGNORING: " + pId + " <> " + qId );
						ignore = true;
					}
				}

				if ( ignore )
					continue;

				if ( zLimits.containsKey( qZ ) )
				{
					final int limit = zLimits.get( qZ );

					if ( Math.abs( qTileSpec.getZ() - pTileSpec.getZ() ) > limit )
					{
						System.out.println( "IGNORING: " + pId + " <> " + qId );
						ignore = true;
					}
				}

				if ( ignore )
					continue;

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !idToTileMap.containsKey( pId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = SolveTools.buildTileFromSpec(parameters, pTileSpec);
					p = pairP.getA();
					idToTileMap.put( pId, p );
					idToPreviousModel.put( pId, pairP.getB() );
					idToTileSpec.put( pId, pTileSpec );

					if ( pTileSpec.getZ() <= topBorder )
						topTileIds.add( pId );

					if ( pTileSpec.getZ() >= bottomBorder )
						bottomTileIds.add( pId );
				}
				else
				{
					p = idToTileMap.get( pId );
				}

				if ( !idToTileMap.containsKey( qId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = SolveTools.buildTileFromSpec(parameters, qTileSpec);
					q = pairQ.getA();
					idToTileMap.put( qId, q );
					idToPreviousModel.put( qId, pairQ.getB() );
					idToTileSpec.put( qId, qTileSpec );	

					if ( qTileSpec.getZ() <= topBorder )
						topTileIds.add( qId );

					if ( qTileSpec.getZ() >= bottomBorder )
						bottomTileIds.add( qId );
				}
				else
				{
					q = idToTileMap.get( qId );
				}

				p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
				connectedTiles.add( pId + " <> " + qId );
			}
		}


		for ( final String s : connectedTiles )
			System.out.println( s );

		//System.exit( 0 );

		LOG.info("top block #tiles " + topTileIds.size());
		LOG.info("bottom block #tiles " + bottomTileIds.size());

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

			int numIterations = parameters.maxIterations;
			if ( lambda == 0.5 )
				numIterations = 1000;
			else if ( lambda == 0.1 )
				numIterations = 400;
			else if ( lambda == 0.01 )
				numIterations = 200;

			// tileConfig.optimize(parameters.maxAllowedError, parameters.maxIterations, parameters.maxPlateauWidth);
		
			LOG.info( "l=" + lambda + ", numIterations=" + numIterations );

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
		// create lookup for the new models
		//
		final HashMap<String, AffineModel2D> idToNewModel = new HashMap<>();

		final ArrayList< String > tileIds = new ArrayList<>( idToTileMap.keySet() );
		Collections.sort( tileIds );

		for (final String tileId : tileIds )
		{
			final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);
			AffineModel2D affine = tile.getModel().createAffineModel2D();

			idToNewModel.put( tileId, affine );
			LOG.info("tile {} model is {}", tileId, affine);
		}

		//
		// Compute a smooth rigid transition between the remaining blocks on top and bottom and the re-aligned section
		//
		final Tile<RigidModel2D> topBlock = new Tile<>( new RigidModel2D());
		final Tile<RigidModel2D> reAlignedBlock = new Tile<>( new RigidModel2D());
		final Tile<RigidModel2D> bottomBlock = new Tile<>( new RigidModel2D());

		final int samplesPerDimension = 5;

		// link top and realigned block
		final List<PointMatch> matchesTop = new ArrayList<>();

		for ( final String tileId : topTileIds )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			final AffineModel2D previousModel = idToPreviousModel.get( tileId );
			final AffineModel2D newModel = idToNewModel.get( tileId );

			// make a regular grid
			final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
			final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

			for (int y = 0; y < samplesPerDimension; ++y)
			{
				final double sampleY = y * sampleHeight;
				for (int x = 0; x < samplesPerDimension; ++x)
				{
					final double[] p = new double[] { x * sampleWidth, sampleY };
					final double[] q = new double[] { x * sampleWidth, sampleY };

					previousModel.applyInPlace( p );
					newModel.applyInPlace( q );

					matchesTop.add(new PointMatch( new Point(p), new Point(q) ));
				}
			}
		}

		topBlock.connect( reAlignedBlock, matchesTop );

		// link realigned block and bottom
		final List<PointMatch> matchesBottom = new ArrayList<>();

		for ( final String tileId : bottomTileIds )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			final AffineModel2D previousModel = idToPreviousModel.get( tileId );
			final AffineModel2D newModel = idToNewModel.get( tileId );

			// make a regular grid
			final double sampleWidth = (tileSpec.getWidth() - 1.0) / (samplesPerDimension - 1.0);
			final double sampleHeight = (tileSpec.getHeight() - 1.0) / (samplesPerDimension - 1.0);

			for (int y = 0; y < samplesPerDimension; ++y)
			{
				final double sampleY = y * sampleHeight;
				for (int x = 0; x < samplesPerDimension; ++x)
				{
					final double[] p = new double[] { x * sampleWidth, sampleY };
					final double[] q = new double[] { x * sampleWidth, sampleY };

					newModel.applyInPlace( p );
					previousModel.applyInPlace( q );

					matchesBottom.add(new PointMatch( new Point(p), new Point(q) ));
				}
			}
		}

		reAlignedBlock.connect( bottomBlock, matchesBottom );

		// solve the simple system
		final TileConfiguration tileConfigBlocks = new TileConfiguration();
		tileConfigBlocks.addTile( topBlock );
		tileConfigBlocks.addTile( reAlignedBlock );
		tileConfigBlocks.addTile( bottomBlock );

		// fix the top of the stack
		tileConfigBlocks.fixTile( topBlock );

		LOG.info( "Optimizing ... " );

		//tileConfigBlocks.preAlign();
		
		final float damp = 1.0f;
		TileUtil.optimizeConcurrently(
				new ErrorStatistic(parameters.maxPlateauWidth + 1 ),
				parameters.maxAllowedError,
				10000,
				10000,
				damp,
				tileConfigBlocks,
				tileConfigBlocks.getTiles(),
				tileConfigBlocks.getFixedTiles(),
				1);

		LOG.info( "TOP block: " + topBlock.getModel() );
		LOG.info( "REALIGN block: " + reAlignedBlock.getModel() );
		LOG.info( "BOTTOM block: " + bottomBlock.getModel() );

		final AffineModel2D topBlockModel = SolveTools.createAffineModel( topBlock.getModel() );
		final AffineModel2D reAlignBlockModel = SolveTools.createAffineModel( reAlignedBlock.getModel() );
		final AffineModel2D bottomBlockModel = SolveTools.createAffineModel( bottomBlock.getModel() );

		// assemble the final transformation models
		//
		// - the new top models are the oldModels preconcatenated
		//		with the top result of this solve
		// - the new resolved models within the top or bottom region 
		//		are the resolved models preconcatenated with the realign
		//		result of this solve, interpolated with the top/bottom
		// - the realigned models not within top or bottom are the resolved models
		//		preconcatenated with the realign
		// - the new bottom models are the oldModels preconcatenated
		//		with the bottom result of this solve

		final HashMap<String, AffineModel2D> idToFinalModel = new HashMap<>();

		for ( final String tileId : tileIds )
		{
			final TileSpec tileSpec = idToTileSpec.get( tileId );
			final double z = tileSpec.getZ();

			// previous and resolved model for the current tile
			final AffineModel2D previousModel = idToPreviousModel.get( tileId ).copy();
			final AffineModel2D newModel = idToNewModel.get( tileId ).copy();

			final AffineModel2D tileModel;

			if ( z <= topBorder ) // in the top block
			{
				// goes from 0.0 to 1.0 as z increases
				final double lambda = 1.0 - (topBorder - z) / (double)(overlapTop-1);

				// the first model for the tile is the one from the top block on top of previous state
				previousModel.preConcatenate( topBlockModel );

				// the second model for the tile is the one from the re-align block on top of the re-align
				newModel.preConcatenate( reAlignBlockModel );

				tileModel = new InterpolatedAffineModel2D<>( previousModel, newModel, lambda ).createAffineModel2D();
			}
			else if ( z >= bottomBorder ) // in the bottom block
			{
				// goes from 1.0 to 0.0 as z increases
				final double lambda = 1.0 - (z - bottomBorder) / (double)(overlapBottom-1);

				// the first model for the tile is the one from the bottom block on top of previous state
				previousModel.preConcatenate( bottomBlockModel );

				// the second model for the tile is the one from the re-align block on top of the re-align
				newModel.preConcatenate( reAlignBlockModel );

				tileModel = new InterpolatedAffineModel2D<>( previousModel, newModel, lambda ).createAffineModel2D();
			}
			else // in between top and bottom block
			{
				// the model for the tile is the one from the re-align block on top of the re-align
				newModel.preConcatenate( reAlignBlockModel );
				tileModel = newModel;
			}

			idToFinalModel.put( tileId, tileModel );
		}

		if ( parameters.targetStack != null )
		{
			//
			// save the re-aligned part
			//
			final HashSet< Double > zToSaveSet = new HashSet<>();

			for ( final TileSpec ts : idToTileSpec.values() )
				zToSaveSet.add( ts.getZ() );

			List< Double > zToSave = new ArrayList<>( zToSaveSet );
			Collections.sort( zToSave );

			LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );

			SolveTools.saveTargetStackTiles( parameters, runParams, idToFinalModel, null, zToSave, TransformApplicationMethod.REPLACE_LAST );

			//
			// save the bottom part
			//
			zToSave = runParams.renderDataClient.getStackZValues( parameters.stack, zToSave.get( zToSave.size() - 1 ) + 0.1, null );

			LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );

			SolveTools.saveTargetStackTiles( parameters, runParams, null, bottomBlockModel, zToSave, TransformApplicationMethod.PRE_CONCATENATE_LAST );

			// TODO: save the top too when necessary
			//
			// save the top part
			//
			zToSave = runParams.renderDataClient.getStackZValues( parameters.stack, null, runParams.minZ - 0.1 );

			LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );
			SolveTools.saveTargetStackTiles( parameters, runParams, null, null, zToSave, null );

			// complete the stack after everything has been saved
			SolveTools.completeStack(parameters, runParams);
		}

		new ImageJ();

		// visualize new result
		ImagePlus imp1 = SolveTools.render( idToFinalModel, idToTileSpec, 0.15 );
		imp1.setTitle( "final" );

		//ImagePlus imp2 = render( idToNewModel, idToTileSpec, 0.15 );
		//imp2.setTitle( "realign" );

		ImagePlus imp3 = SolveTools.render( idToPreviousModel, idToTileSpec, 0.15 );
		imp3.setTitle( "previous" );

		SimpleMultiThreading.threadHaltUnClean();

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
                            "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_19m",
                            "--project", "Sec08",
                            "--stack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758",
                            //"--targetStack", "v2_py_solve_03_affine_e10_e10_trakem2_22103_15758_new",
                            "--regularizerModelType", "RIGID",
                            "--optimizerLambdas", "1.0, 0.5, 0.1, 0.01",
                            "--minZ", "15718",//"24700",
                            "--maxZ", "15810",//"26650",

                            "--threads", "4",
                            "--maxIterations", "10000",
                            "--completeTargetStack",
                            "--matchCollection", "Sec08_patch_matt"
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
