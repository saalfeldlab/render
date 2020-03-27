package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.janelia.alignment.match.CanvasMatchResult;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel2D;
import net.imglib2.util.Pair;

public class DistributedSolveWorker< B extends Model< B > & Affine2D< B > >
{
	final Parameters parameters;
	final RunParameters runParams;
	final SolveItem< B > inputSolveItem;
	final ArrayList< SolveItem< B > > solveItems;

	public DistributedSolveWorker( final Parameters parameters, final SolveItem< B > solveItem )
	{
		this.parameters = parameters;
		this.inputSolveItem = solveItem;
		this.runParams = solveItem.runParams();

		this.solveItems = new ArrayList<>();
	}

	public SolveItem< B > getInputSolveItems() { return inputSolveItem; }
	public ArrayList< SolveItem< B > > getSolveItems() { return solveItems; }

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		assembleMatchData();
		split(); // splits

		for ( final SolveItem< B > solveItem : solveItems )
			solve( solveItem, parameters );
	}

	protected void split()
	{
		final ArrayList< Set< Tile< ? > > > graphs = Tile.identifyConnectedGraphs( inputSolveItem.idToTileMap().values() );

		LOG.info( "Graph of SolveItem " + inputSolveItem.getId() + " consists of " + graphs.size() + " subgraphs." );

		if ( graphs.size() == 1 )
			solveItems.add( inputSolveItem );
		else
			throw new RuntimeException( "Stack is not connected, splitting not implemented yet." );
	}

	protected void solve(
			final SolveItem< B > solveItem,
			final Parameters parameters
			) throws InterruptedException, ExecutionException
	{
		final TileConfiguration tileConfig = new TileConfiguration();

		tileConfig.addTiles(solveItem.idToTileMap().values());

		LOG.info("run: optimizing {} tiles", solveItem.idToTileMap().size());

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
			for (final Tile tile : solveItem.idToTileMap().values())
				((InterpolatedAffineModel2D) tile.getModel()).setLambda(lambda);

			int numIterations = parameters.maxIterations;
			if ( lambda == 1.0 || lambda == 0.5 )
				numIterations = 100;
			else if ( lambda == 0.1 )
				numIterations = 40;
			else if ( lambda == 0.01 )
				numIterations = 20;

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
		solveItem.idToNewModel().clear();

		final ArrayList< String > tileIds = new ArrayList<>( solveItem.idToTileMap().keySet() );
		Collections.sort( tileIds );

		for (final String tileId : tileIds )
		{
			final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = solveItem.idToTileMap().get(tileId);
			final AffineModel2D affine = tile.getModel().createAffineModel2D();

			/*
			// TODO: REMOVE
			if ( inputSolveItem.getId() == 2 )
			{
			final TranslationModel2D t = new TranslationModel2D();
			t.set( 1000, 0 );
			affine.preConcatenate( t );
			}
			*/

			solveItem.idToNewModel().put( tileId, affine );
			LOG.info("tile {} model is {}", tileId, affine);
		}
		
	}

	protected void assembleMatchData() throws IOException
	{
		LOG.info( "Loading transforms and matches from " + runParams.minZ + " to layer " + runParams.maxZ );

		// TODO: only fetch the ones we actually need here
		for (final String pGroupId : runParams.pGroupList)
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

				// if any of the matches is outside the range we ignore them
				if ( pTileSpec.getZ() < inputSolveItem.minZ() || pTileSpec.getZ() > inputSolveItem.maxZ() || qTileSpec.getZ() < inputSolveItem.minZ() || qTileSpec.getZ() > inputSolveItem.maxZ() )
				{
					LOG.info("run: ignoring pair ({}, {}) because it is out of range {}", pId, qId, parameters.stack);
					continue;
				}

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !inputSolveItem.idToTileMap().containsKey( pId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = SolveTools.buildTileFromSpec(parameters, pTileSpec);
					p = pairP.getA();
					inputSolveItem.idToTileMap().put( pId, p );
					inputSolveItem.idToPreviousModel().put( pId, pairP.getB() );
					inputSolveItem.idToTileSpec().put( pId, pTileSpec );
				}
				else
				{
					p = inputSolveItem.idToTileMap().get( pId );
				}

				if ( !inputSolveItem.idToTileMap().containsKey( qId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = SolveTools.buildTileFromSpec(parameters, qTileSpec);
					q = pairQ.getA();
					inputSolveItem.idToTileMap().put( qId, q );
					inputSolveItem.idToPreviousModel().put( qId, pairQ.getB() );
					inputSolveItem.idToTileSpec().put( qId, qTileSpec );	
				}
				else
				{
					q = inputSolveItem.idToTileMap().get( qId );
				}

				p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				inputSolveItem.zToTileId().putIfAbsent( pZ, new HashSet<>() );
				inputSolveItem.zToTileId().putIfAbsent( qZ, new HashSet<>() );

				inputSolveItem.zToTileId().get( pZ ).add( pId );
				inputSolveItem.zToTileId().get( qZ ).add( qId );
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveWorker.class);
}
