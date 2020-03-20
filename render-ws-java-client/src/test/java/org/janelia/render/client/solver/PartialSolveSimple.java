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
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection.TransformApplicationMethod;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;

public class PartialSolveSimple< B extends Model< B > & Affine2D< B > >
{
	final Parameters parameters;
	final RunParameters runParams;

	public PartialSolveSimple(final Parameters parameters) throws IOException
	{
		this.parameters = parameters;
		this.runParams = SolveTools.setupSolve( parameters );
	}

	protected void run() throws IOException, ExecutionException, InterruptedException, NoninvertibleModelException
	{
		LOG.info("run: entry");

		LOG.info( "fixing tiles from layer " + runParams.minZ );
		LOG.info( "grouping tiles from layer " + runParams.maxZ );

		final HashMap<String, Tile<InterpolatedAffineModel2D<AffineModel2D, B>>> idToTileMap = new HashMap<>();
		final HashMap<String, AffineModel2D> idToPreviousModel = new HashMap<>();
		final HashMap<String, TileSpec> idToTileSpec = new HashMap<>();

		final List< Tile<InterpolatedAffineModel2D<AffineModel2D, B>> > fixedTiles = new ArrayList<>();
		final List< String > fixedTileNames = new ArrayList<>();

		// we want group all tiles of the last layer to have a common transformation that we can propgate through the rest of the stack
		// therefore, we also want a differential transform for the grouped tiles, which means that we have to apply the current 
		// transformation to the local points
		final HashSet< String > groupedTiles = new HashSet<>(); 
		final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> groupedTile = new Tile<>(
				new InterpolatedAffineModel2D<>(
					new AffineModel2D(),
					parameters.regularizerModelType.getInstance(),
					parameters.startLambda)); // note: lambda gets reset during optimization loops

		ArrayList< String > log = new ArrayList<>();

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

				boolean pGrouped = false;
				boolean qGrouped = false;

				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> p, q;

				if ( !idToTileMap.containsKey( pId ) )
				{
					// isgrouped?
					if ( Math.round( pTileSpec.getZ() ) == Math.round( runParams.maxZ ) )
					{
						pGrouped = true;
						groupedTiles.add( pId );
						log.add( "Adding " + pId + "(grouped=" + pGrouped + ") to grouped" );

						p = groupedTile;
						idToTileMap.put( pId, groupedTile );
						idToPreviousModel.put( pId, SolveTools.loadLastTransformFromSpec( pTileSpec ).copy() );
						idToTileSpec.put( pId, pTileSpec );
					}
					else
					{
						pGrouped = false;
						final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairP = SolveTools.buildTileFromSpec(parameters, pTileSpec);
						p = pairP.getA();
						idToTileMap.put( pId, p );
						idToPreviousModel.put( pId, pairP.getB() );
						idToTileSpec.put( pId, pTileSpec );
					}

					// isfixed?
					if ( Math.round( pTileSpec.getZ() ) == Math.round( runParams.minZ ) )
					{
						fixedTiles.add( p );
						fixedTileNames.add( pId );
					}
				}
				else
				{
					p = idToTileMap.get( pId );

					if ( groupedTiles.contains( pId ))
						pGrouped = true;
					else
						pGrouped = false;
				}

				if ( !idToTileMap.containsKey( qId ) )
				{
					// isgrouped?
					if ( Math.round( qTileSpec.getZ() ) == Math.round( runParams.maxZ ) )
					{
						qGrouped = true;
						groupedTiles.add( qId );
						log.add( "Adding " + qId + "(grouped=" + qGrouped + ") to grouped" );

						q = groupedTile;
						idToTileMap.put( qId, groupedTile );
						idToPreviousModel.put( qId, SolveTools.loadLastTransformFromSpec( qTileSpec ).copy() );
						idToTileSpec.put( qId, qTileSpec );
					}
					else
					{
						qGrouped = false;
						final Pair< Tile<InterpolatedAffineModel2D<AffineModel2D, B>>, AffineModel2D > pairQ = SolveTools.buildTileFromSpec(parameters, qTileSpec);
						q = pairQ.getA();
						idToTileMap.put( qId, q );
						idToPreviousModel.put( qId, pairQ.getB() );
						idToTileSpec.put( qId, qTileSpec );	
					}

					// isfixed?
					if ( Math.round( qTileSpec.getZ() ) == Math.round( runParams.minZ ) )
					{
						fixedTiles.add( q );
						fixedTileNames.add( qId );
					}
				}
				else
				{
					q = idToTileMap.get( qId );

					if ( groupedTiles.contains( qId ))
						qGrouped = true;
					else
						qGrouped = false;
				}

				// both images are not grouped, this is the "classical" case
				if ( !pGrouped && !qGrouped )
				{
					p.connect(q, CanvasMatchResult.convertMatchesToPointMatchList(match.getMatches()));
				}
				else if ( pGrouped && qGrouped )
				{
					// both images are part of the same grouped tile, nothing to do
					log.add( "Ignoring matches between " + pId + "(grouped=" + pGrouped + ") <> " + qId + "(grouped=" + qGrouped + ")" );
				}
				else // either p or q is grouped
				{
					// as we cannot build the grouped Tile yet, we need to remember all connections to it
					log.add( "Putting grouped matches between " + pId + "(grouped=" + pGrouped + ") <> " + qId + "(grouped=" + qGrouped + ")" );

					final AffineModel2D pModel = pGrouped ? idToPreviousModel.get( pId ) : null;
					final AffineModel2D qModel = qGrouped ? idToPreviousModel.get( qId ) : null;

					log.add( "pModel: " + pModel + " qModel: " + qModel  );

					p.connect(q, convertMatchesToPointMatchListRelative(match.getMatches(), pModel, qModel ));
				}

			}
		}

		LOG.info( "Fixed tiles:" );

		for ( int i = 0; i < fixedTiles.size(); ++i )
			LOG.info( fixedTileNames.get( i ) + " " + fixedTiles.get( i ) + " model: " + idToPreviousModel.get( fixedTileNames.get( i ) ) );

		LOG.info( "Grouped tiles:" );

		for ( final String tile : groupedTiles )
			LOG.info( tile );

		LOG.info( "Grouped matches:" );

		for ( final String tile : log )
			LOG.info( tile );

		//System.exit( 0 );

		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles(idToTileMap.values());

		for ( final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> t : fixedTiles )
			tileConfig.fixTile( t );
		
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

		if (parameters.targetStack == null)
		{
			final HashMap<String, AffineModel2D> idToNewModel = new HashMap<>();

			AffineModel2D propagationModel = null;

			final ArrayList< String > tileIds = new ArrayList<>( idToTileMap.keySet() );
			Collections.sort( tileIds );

			for (final String tileId : tileIds )
			{
				final Tile<InterpolatedAffineModel2D<AffineModel2D, B>> tile = idToTileMap.get(tileId);
				final InterpolatedAffineModel2D model = tile.getModel();
				AffineModel2D affine = model.createAffineModel2D();

				if ( groupedTiles.contains( tileId ) )
				{
					// the relative propagation model of the last z plane
					if ( propagationModel == null && idToTileSpec.get( tileId ).getZ() == runParams.maxZ && groupedTiles.contains( tileId ) )
						propagationModel = affine.copy();

					final AffineModel2D previous = idToPreviousModel.get( tileId ).copy();

					LOG.info( "tile {} model is grouped and therefore a relative model {}", tileId, affine);
					LOG.info( "concatenating with previous model {} ", previous );

					previous.preConcatenate( affine );
					affine = previous;
				}

				idToNewModel.put( tileId, affine );
				LOG.info("tile {} model is {}", tileId, affine);
			}

			LOG.info("");
			LOG.info("Relative propagation model for all layers > " + runParams.maxZ + " = " + propagationModel );
			LOG.info("");

			// save the re-aligned part
			final HashSet< Double > zToSaveSet = new HashSet<>();

			for ( final TileSpec ts : idToTileSpec.values() )
				zToSaveSet.add( ts.getZ() );

			List< Double > zToSave = new ArrayList<>( zToSaveSet );
			Collections.sort( zToSave );

			LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );

			SolveTools.saveTargetStackTiles( parameters, runParams, idToNewModel, null, zToSave, TransformApplicationMethod.REPLACE_LAST );

			// save the bottom part
			zToSave = runParams.renderDataClient.getStackZValues( parameters.stack, zToSave.get( zToSave.size() - 1 ) + 0.1, null );

			LOG.info("Saving from " + zToSave.get( 0 ) + " to " + zToSave.get( zToSave.size() - 1 ) );

			SolveTools.saveTargetStackTiles( parameters, runParams, null, propagationModel, zToSave, TransformApplicationMethod.PRE_CONCATENATE_LAST );

			//new ImageJ();

			// visualize new result
			//render( idToNewModel, idToTileSpec, 0.15 );

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

	/**
	 * @param matches
	 *            point match list in {@link Matches} form.
	 * @param pModel
	 *            affine model that will transform the p points (can be used to
	 *            extract relative models from the optimization) or will do
	 *            nothing if pModel == null
	 * @param qModel
	 *            affine model that will transform the q points (can be used to
	 *            extract relative models from the optimization) or will do
	 *            nothing if qModel == null
	 *
	 * @return the corresponding list of {@link PointMatch} objects.
	 */
	public static List< PointMatch > convertMatchesToPointMatchListRelative(
			final Matches matches, final AffineModel2D pModel,
			final AffineModel2D qModel )
	{
		final double[] w = matches.getWs();

		final int pointMatchCount = w.length;
		final List< PointMatch > pointMatchList = new ArrayList<>( pointMatchCount );

		if ( pointMatchCount > 0 )
		{
			final double[][] p = matches.getPs();
			final double[][] q = matches.getQs();

			final int dimensionCount = p.length;

			for (int matchIndex = 0; matchIndex < pointMatchCount; matchIndex++)
			{
				final double[] pLocal = new double[ dimensionCount ];
				final double[] qLocal = new double[ dimensionCount ];

				for (int dimensionIndex = 0; dimensionIndex < dimensionCount; dimensionIndex++)
				{
					pLocal[ dimensionIndex ] = p[ dimensionIndex ][ matchIndex ];
					qLocal[ dimensionIndex ] = q[ dimensionIndex ][ matchIndex ];
				}

				if ( pModel != null )
					pModel.applyInPlace( pLocal );

				if ( qModel != null )
					qModel.applyInPlace( qLocal );

				pointMatchList.add( new PointMatch( new Point( pLocal ), new Point( qLocal ), w[ matchIndex ] ) );
			}
		}

		return pointMatchList;
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

                            "--stack", "v2_patch_trakem2",
                            //"--targetStack", "v2_patch_trakem2_sp",
                            "--regularizerModelType", "RIGID",
                            "--optimizerLambdas", "1.0, 0.5, 0.1",
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

                final PartialSolveSimple client = new PartialSolveSimple(parameters);

                client.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(PartialSolveSimple.class);
}
