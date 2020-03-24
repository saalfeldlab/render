package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
import net.imglib2.util.Pair;

public class DistributedSolveWorker< B extends Model< B > & Affine2D< B > >
{
	final Parameters parameters;
	final RunParameters runParams;
	final SolveItem< B > solveItem;

	public DistributedSolveWorker( final Parameters parameters, final SolveItem< B > solveItem )
	{
		this.parameters = parameters;
		this.solveItem = solveItem;
		this.runParams = solveItem.runParams();
	}

	public SolveItem< B > getSolveItem() { return solveItem; }

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		assembleMatchData();
		solve();
	}

	protected void solve() throws InterruptedException, ExecutionException
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
			AffineModel2D affine = tile.getModel().createAffineModel2D();

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
				if ( pTileSpec.getZ() < solveItem.minZ() || pTileSpec.getZ() > solveItem.maxZ() || qTileSpec.getZ() < solveItem.minZ() || qTileSpec.getZ() > solveItem.maxZ() )
				{
					LOG.info("run: ignoring pair ({}, {}) because it is out of range {}", pId, qId, parameters.stack);
					continue;
				}

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !solveItem.idToTileMap().containsKey( pId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = SolveTools.buildTileFromSpec(parameters, pTileSpec);
					p = pairP.getA();
					solveItem.idToTileMap().put( pId, p );
					solveItem.idToPreviousModel().put( pId, pairP.getB() );
					solveItem.idToTileSpec().put( pId, pTileSpec );
				}
				else
				{
					p = solveItem.idToTileMap().get( pId );
				}

				if ( !solveItem.idToTileMap().containsKey( qId ) )
				{
					final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = SolveTools.buildTileFromSpec(parameters, qTileSpec);
					q = pairQ.getA();
					solveItem.idToTileMap().put( qId, q );
					solveItem.idToPreviousModel().put( qId, pairQ.getB() );
					solveItem.idToTileSpec().put( qId, qTileSpec );	
				}
				else
				{
					q = solveItem.idToTileMap().get( qId );
				}

				p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));

				final int pZ = (int)Math.round( pTileSpec.getZ() );
				final int qZ = (int)Math.round( qTileSpec.getZ() );

				solveItem.zToTileId().putIfAbsent( pZ, new HashSet<>() );
				solveItem.zToTileId().putIfAbsent( qZ, new HashSet<>() );

				solveItem.zToTileId().get( pZ ).add( pId );
				solveItem.zToTileId().get( qZ ).add( qId );
			}
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveWorker.class);
}
