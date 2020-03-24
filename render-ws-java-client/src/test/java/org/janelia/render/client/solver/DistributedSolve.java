package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class DistributedSolve
{
	final Parameters parameters;
	final RunParameters runParams;

	public DistributedSolve( final Parameters parameters ) throws IOException
	{
		this.parameters = parameters;
		this.runParams = SolveTools.setupSolve( parameters );

		// TODO: load matches only once, not for each thread
		// assembleMatchData( parameters, runParams );
	}

	public void run( final int setSize )
	{
		final int minZ = (int)Math.round( this.runParams.minZ );
		final int maxZ = (int)Math.round( this.runParams.maxZ );

		final SolveSet solveSet = defineSolveSet( minZ, maxZ, setSize, runParams );

		LOG.info( "Defined sets for global solve" );
		LOG.info( "\n" + solveSet );

		// Multithreaded for now (should be Spark for cluster-)

		// set up executor service
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( 3 );
		final ArrayList< Callable< Void > > tasks = new ArrayList<>();

		for ( final SolveItem< ? > s : solveSet.allItems() )
		{
			tasks.add( new Callable< Void >() 
			{
				@Override
				public Void call() throws Exception
				{
					new DistributedSolveWorker( parameters, s ).run();
	
					return null;
				}
			});
		}

		try
		{
			// invokeAll() returns when all tasks are complete
			taskExecutor.invokeAll( tasks );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute alignments: " + e );
			e.printStackTrace();
		}

		taskExecutor.shutdown();

		try
		{
			globalSolve( solveSet );
		}
		catch ( NotEnoughDataPointsException | IllDefinedDataPointsException | InterruptedException | ExecutionException | NoninvertibleModelException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		/*
		new ImageJ();

		for ( final SolveItem leftItem : solveSet.allItems() )
			leftItem.visualizeAligned().setTitle( "aligend " + leftItem.minZ() + " >> " + leftItem.maxZ() );

		SimpleMultiThreading.threadHaltUnClean();
		*/
	}

	protected void globalSolve( final SolveSet solveSet ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException, NoninvertibleModelException
	{
		final HashMap<String, AffineModel2D> idToFinalModelGlobal = new HashMap<>();

		final HashMap<String, TileSpec> idToTileSpecGlobal = new HashMap<>();
		final HashMap<Integer, HashSet<String> > zToTileIdGlobal = new HashMap<>();
		final HashMap<Integer, Pair< SolveItem< ? >, SolveItem< ? > > > zToLeftRightItem = new HashMap<>();

		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		for ( int i = 0; i < solveSet.leftItems.size() - 1; ++i )
		{
			// we connect the left block to the right block and back to left block below
			final SolveItem< ? > left = solveSet.leftItems.get( i );
			final SolveItem< ? > right = solveSet.rightItems.get( i );
			final SolveItem< ? > leftPlus1 = solveSet.leftItems.get( i + 1 );

			// first the left block to the right one
			for ( int z = right.minZ(); z <= left.maxZ(); ++z )
			{
				// get tileIds for each z section (they are identical for left and right)
				final HashSet< String > tileIds = left.zToTileId().get( z );

				// if a section is not present
				if ( tileIds == null )
					continue;

				zToTileIdGlobal.put( z, tileIds );
				zToLeftRightItem.put( z, new ValuePair<>( left, right ) );

				final List< PointMatch > matchesLeftToRight = new ArrayList<>();

				for ( final String tileId : tileIds )
				{
					// tilespec is identical, too
					final TileSpec tileSpec = right.idToTileSpec().get( tileId );

					idToTileSpecGlobal.put( tileId, tileSpec );

					final AffineModel2D modelA = left.idToNewModel().get( tileId );
					final AffineModel2D modelB = right.idToNewModel().get( tileId );

					// make a regular grid
					final double sampleWidth = (tileSpec.getWidth() - 1.0) / (SolveItem.samplesPerDimension - 1.0);
					final double sampleHeight = (tileSpec.getHeight() - 1.0) / (SolveItem.samplesPerDimension - 1.0);

					for (int y = 0; y < SolveItem.samplesPerDimension; ++y)
					{
						final double sampleY = y * sampleHeight;
						for (int x = 0; x < SolveItem.samplesPerDimension; ++x)
						{
							final double[] p = new double[] { x * sampleWidth, sampleY };
							final double[] q = new double[] { x * sampleWidth, sampleY };

							modelA.applyInPlace( p );
							modelB.applyInPlace( q );

							matchesLeftToRight.add(new PointMatch( new Point(p), new Point(q) ));
						}
					}
				}

				left.globalAlignBlock.connect( right.globalAlignBlock, matchesLeftToRight );
			}

			// now the right block to the second left one
			for ( int z = leftPlus1.minZ(); z <= right.maxZ(); ++z )
			{
				// get tileIds for each z section (they are identical for left and right)
				final HashSet< String > tileIds = right.zToTileId().get( z );

				// if a section is not present
				if ( tileIds == null )
					continue;

				zToTileIdGlobal.put( z, tileIds );
				zToLeftRightItem.put( z, new ValuePair<>( leftPlus1, right ) );

				final List< PointMatch > matchesRightToLeft = new ArrayList<>();

				for ( final String tileId : tileIds )
				{
					// tilespec is identical, too
					final TileSpec tileSpec = right.idToTileSpec().get( tileId );

					idToTileSpecGlobal.put( tileId, tileSpec );

					final AffineModel2D modelA = right.idToNewModel().get( tileId );
					final AffineModel2D modelB = leftPlus1.idToNewModel().get( tileId );

					// make a regular grid
					final double sampleWidth = (tileSpec.getWidth() - 1.0) / (SolveItem.samplesPerDimension - 1.0);
					final double sampleHeight = (tileSpec.getHeight() - 1.0) / (SolveItem.samplesPerDimension - 1.0);

					for (int y = 0; y < SolveItem.samplesPerDimension; ++y)
					{
						final double sampleY = y * sampleHeight;
						for (int x = 0; x < SolveItem.samplesPerDimension; ++x)
						{
							final double[] p = new double[] { x * sampleWidth, sampleY };
							final double[] q = new double[] { x * sampleWidth, sampleY };

							modelA.applyInPlace( p );
							modelB.applyInPlace( q );

							matchesRightToLeft.add(new PointMatch( new Point(p), new Point(q) ));
						}
					}
				}

				right.globalAlignBlock.connect( leftPlus1.globalAlignBlock, matchesRightToLeft );
			}

			// solve the simple system

			if ( i == 0 )
				tileConfigBlocks.addTile( left.globalAlignBlock );

			tileConfigBlocks.addTile( right.globalAlignBlock );
			tileConfigBlocks.addTile( leftPlus1.globalAlignBlock );
		}

		// do not fix anything
		// tileConfigBlocks.fixTile( left.globalAlignBlock );

		LOG.info( "Pre-Align ... " );

		tileConfigBlocks.preAlign();

		LOG.info( "Optimizing ... " );
		
		final float damp = 1.0f;
		TileUtil.optimizeConcurrently(
				new ErrorStatistic(parameters.maxPlateauWidth + 1 ),
				parameters.maxAllowedError,
				1000,
				1000,
				damp,
				tileConfigBlocks,
				tileConfigBlocks.getTiles(),
				tileConfigBlocks.getFixedTiles(),
				1);

		for ( int i = 0; i < solveSet.leftItems.size(); ++i )
		{
			final SolveItem< ? > solveItemLeft = solveSet.leftItems.get( i );
			solveItemLeft.globalAlignAffineModel = SolveTools.createAffineModel( solveItemLeft.globalAlignBlock.getModel() );

			LOG.info( "Left block " + i + ": " + solveItemLeft.globalAlignBlock.getModel() );

			if ( i < solveSet.rightItems.size() )
			{
				final SolveItem< ? > solveItemRight = solveSet.rightItems.get( i );
				solveItemLeft.globalAlignAffineModel = SolveTools.createAffineModel( solveItemRight.globalAlignBlock.getModel() );

				LOG.info( "Right block " + i + ": " + solveItemRight.globalAlignBlock.getModel() );
			}
		}

		final ArrayList< Integer > zSections = new ArrayList<>( zToTileIdGlobal.keySet() );
		Collections.sort( zSections );

		for ( final int z : zSections )
		{
			// get tileIds for each z section
			final HashSet< String > tileIds = zToTileIdGlobal.get( z );

			for ( final String tileId : tileIds )
			{
				final Pair< SolveItem< ? >, SolveItem< ? > > solveItemPair = zToLeftRightItem.get( z );

				final AffineModel2D modelA = solveItemPair.getA().globalAlignAffineModel;
				final AffineModel2D modelB = solveItemPair.getB().globalAlignAffineModel;

				LOG.info( "z=" + z + ": " + solveItemPair.getA().getCosWeight( z ) + " " + solveItemPair.getB().getCosWeight( z ) );

				final AffineModel2D tileModel = new InterpolatedAffineModel2D<>( modelA, modelB, solveItemPair.getB().getCosWeight( z ) ).createAffineModel2D();
				
				idToFinalModelGlobal.put( tileId, tileModel );
			}
		}

		// visualize new result
		ImagePlus imp1 = SolveTools.render( idToFinalModelGlobal, idToTileSpecGlobal, 0.15 );
		imp1.setTitle( "final" );

	}

	protected static SolveSet defineSolveSet( final int minZ, final int maxZ, final int setSize, final RunParameters runParams )
	{
		final int modulo = ( maxZ - minZ + 1 ) % setSize;

		final int numSetsLeft = ( maxZ - minZ + 1 ) / setSize + Math.min( 1, modulo );

		final ArrayList< SolveItem > leftSets = new ArrayList<>();
		final ArrayList< SolveItem > rightSets = new ArrayList<>();

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			leftSets.add( new SolveItem( minZ + i * setSize, Math.min( minZ + (i + 1) * setSize - 1, maxZ ), runParams ) );
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final SolveItem set0 = leftSets.get( i );
			final SolveItem set1 = leftSets.get( i + 1 );

			rightSets.add( new SolveItem( ( set0.minZ() + set0.maxZ() ) / 2, ( set1.minZ() + set1.maxZ() ) / 2, runParams ) );
		}

		return new SolveSet( (ArrayList)leftSets, (ArrayList)rightSets );
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
                            "--minZ", "10000",
                            "--maxZ", "10199",

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

                final DistributedSolve solve = new DistributedSolve( parameters );
                solve.run( 100 );
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolve.class);
}
