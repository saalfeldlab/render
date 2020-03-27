package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class DistributedSolve
{
	final Parameters parameters;
	final RunParameters runParams;

	public DistributedSolve( final Parameters parameters ) throws IOException
	{
		this.parameters = parameters;
		this.runParams = SolveTools.setupSolve( parameters );

		// each job uses just one thread
		this.parameters.numberOfThreads = 1;

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
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( 8 );
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

	protected static HashSet< String > commonStrings( final HashSet< String > tileIdsA, final HashSet< String > tileIdsB )
	{
		final HashSet< String > commonStrings = new HashSet<>();

		for ( final String a : tileIdsA )
			if ( tileIdsB.contains( a ) )
				commonStrings.add( a );

		return commonStrings;
	}

	protected static boolean pairExists(
			final int z,
			final SolveItem< ? > solveItemA,
			final SolveItem< ? > solveItemB,
			final HashMap<Integer, ArrayList< Pair< Pair< SolveItem< ? >, SolveItem< ? > >, HashSet< String > > > > zToSolveItemPairs )
	{
		if ( zToSolveItemPairs.containsKey( z ) )
		{
			final ArrayList< Pair< Pair< SolveItem< ? >, SolveItem< ? > >, HashSet< String > > > entries = zToSolveItemPairs.get( z );

			for ( final Pair< Pair< SolveItem< ? >, SolveItem< ? > >, HashSet< String > > entry : entries )
				if (entry.getA().getA().equals( solveItemA ) && entry.getA().getB().equals( solveItemB ) ||
					entry.getA().getA().equals( solveItemB ) && entry.getA().getB().equals( solveItemA ) )
						return true;

			return false;
		}
		else
		{
			return false;
		}
	}

	protected void globalSolve( final SolveSet solveSet ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException, NoninvertibleModelException
	{
		final HashMap<String, AffineModel2D> idToFinalModelGlobal = new HashMap<>();

		final HashMap<String, TileSpec> idToTileSpecGlobal = new HashMap<>();
		final HashMap<Integer, HashSet<String> > zToTileIdGlobal = new HashMap<>();
		final HashMap<Integer, ArrayList< Pair< Pair< SolveItem< ? >, SolveItem< ? > >, HashSet< String > > > > zToSolveItemPairs = new HashMap<>();

		final TileConfiguration tileConfigBlocks = new TileConfiguration();

		// important: all images within one solveitem must be connected to each other!

		// solve by solveitem, not by z layer
		final List< SolveItem< ? > > allSolveItems = solveSet.allItems();

		for ( int a = 0; a < allSolveItems.size() - 1; ++a )
		{
			final SolveItem< ? > solveItemA = allSolveItems.get( a );

			for ( int z = solveItemA.minZ(); z <= solveItemA.maxZ(); ++z )
			{
				// is this zPlane overlapping with anything?
				boolean hasOverlap = false;

				for ( int b = a + 1; b < allSolveItems.size(); ++b )
				{
					final SolveItem< ? > solveItemB = allSolveItems.get( b );

					if ( solveItemA.equals( solveItemB ) )
						continue;

					// is overlapping
					if ( z >= solveItemB.minZ() && z <= solveItemB.maxZ() )
					{
						// every pair exists twice
						if ( pairExists( z, solveItemA, solveItemB, zToSolveItemPairs ) )
							continue;

						// get tileIds for each z section (they might only be overlapping)
						final HashSet< String > tileIdsA = solveItemA.zToTileId().get( z );
						final HashSet< String > tileIdsB = solveItemB.zToTileId().get( z );

						// if a section is not present
						if ( tileIdsA == null || tileIdsB == null )
							continue;

						// which tileIds are the same between solveItemA and solveItemB
						final HashSet< String > tileIds = commonStrings( tileIdsA, tileIdsB );

						// if there are none, we continue with the next
						if ( tileIds.size() == 0 )
							continue;

						zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
						zToSolveItemPairs.putIfAbsent( z, new ArrayList<>() );

						// remember which solveItems defined which tileIds of this z section
						zToSolveItemPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );

						final List< PointMatch > matchesAtoB = new ArrayList<>();

						for ( final String tileId : tileIds )
						{
							// tilespec is identical
							final TileSpec tileSpec = solveItemA.idToTileSpec().get( tileId );

							// remember the tileids and tileSpecs
							zToTileIdGlobal.get( z ).add( tileId );
							idToTileSpecGlobal.put( tileId, tileSpec );

							final AffineModel2D modelA = solveItemA.idToNewModel().get( tileId );
							final AffineModel2D modelB = solveItemB.idToNewModel().get( tileId );

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

									matchesAtoB.add(new PointMatch( new Point(p), new Point(q) ));
								}
							}
						}

						solveItemA.globalAlignBlock.connect( solveItemB.globalAlignBlock, matchesAtoB );

						tileConfigBlocks.addTile( solveItemA.globalAlignBlock );
						tileConfigBlocks.addTile( solveItemB.globalAlignBlock );

						hasOverlap = true;
					}
				}

				if ( !hasOverlap )
				{
					// there is no overlap with any other solveItem (should be beginning or end of the entire stack)
					final HashSet< String > tileIds = solveItemA.zToTileId().get( z );
					
					// if there are none, we continue with the next
					if ( tileIds.size() == 0 )
						continue;

					zToTileIdGlobal.putIfAbsent( z, new HashSet<>() );
					zToSolveItemPairs.putIfAbsent( z, new ArrayList<>() );

					// remember which solveItems defined which tileIds of this z section
					
					final SolveItem solveItemB = new DummySolveItem( z );
					zToSolveItemPairs.get( z ).add( new ValuePair<>( new ValuePair<>( solveItemA, solveItemB ), tileIds ) );

					for ( final String tileId : tileIds )
					{
						solveItemB.idToNewModel().put( tileId, new AffineModel2D() );

						// remember the tileids and tileSpecs
						zToTileIdGlobal.get( z ).add( tileId );
						idToTileSpecGlobal.put( tileId, solveItemA.idToTileSpec().get( tileId ) );
					}
				}
			}
		}

		// do not fix anything
		// tileConfigBlocks.fixTile( left.globalAlignBlock );

		LOG.info( "Pre-Align ... " );

		//tileConfigBlocks.preAlign();

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

		for ( final SolveItem< ? > solveItem : solveSet.allItems() )
		{
			solveItem.globalAlignAffineModel = SolveTools.createAffineModel( solveItem.globalAlignBlock.getModel() );

			LOG.info( "Block " + solveItem.getId() + ": " + solveItem.globalAlignBlock.getModel() );
		}
		/*
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
		*/

		final ArrayList< Integer > zSections = new ArrayList<>( zToTileIdGlobal.keySet() );
		Collections.sort( zSections );

		for ( final int z : zSections )
		{
			// for every z section, tileIds might be provided from different overlapping blocks if they were not connected and have been split
			final ArrayList< Pair< Pair< SolveItem< ? >, SolveItem< ? > >, HashSet< String > > > entries = zToSolveItemPairs.get( z );

			for ( final  Pair< Pair< SolveItem< ? >, SolveItem< ? > >, HashSet< String > > entry : entries )
			{
				for ( final String tileId : entry.getB() )
				{
					final Pair< SolveItem< ? >, SolveItem< ? > > solveItemPair = entry.getA();

					final SolveItem< ? > solveItemA = solveItemPair.getA();
					final SolveItem< ? > solveItemB = solveItemPair.getB();

					// Models must be preconcatenated with actual models!!!!
					final AffineModel2D globalModelA = solveItemA.globalAlignAffineModel;
					final AffineModel2D globalModelB = solveItemB.globalAlignAffineModel;
	
					final AffineModel2D modelA = solveItemA.idToNewModel().get( tileId );
					final AffineModel2D modelB = solveItemB.idToNewModel().get( tileId );
	
					modelA.preConcatenate( globalModelA );
					modelB.preConcatenate( globalModelB );

					final double wA = solveItemA.getWeight( z );
					final double wB = solveItemB.getWeight( z );

					// if one of them is zero the model stays at it is
					final double regularizeB;
					final AffineModel2D tileModel;

					if ( wA == 0 && wB == 0 )
						throw new RuntimeException( "Two block with weight 0, this must not happen: " + solveItemA.getId() + ", " + solveItemB.getId() );
					else if ( wA == 0 )
					{
						tileModel = modelB.copy();
						regularizeB = 1;
					}
					else if ( wB == 0 )
					{
						tileModel = modelA.copy();
						regularizeB = 0;
					}
					else
					{
						regularizeB = wB / (wA + wB);
						tileModel = new InterpolatedAffineModel2D<>( modelA, modelB, regularizeB ).createAffineModel2D();
					}

					LOG.info( "z=" + z + ": " + solveItemA.getId() + "-" + wA + " ----- " + solveItemB.getId() + "-" + wB + " ----regB=" + regularizeB );


					idToFinalModelGlobal.put( tileId, tileModel );
				}
			}
		}

		new ImageJ();

		// visualize new result
		ImagePlus imp1 = SolveTools.render( idToFinalModelGlobal, idToTileSpecGlobal, 0.15 );
		imp1.setTitle( "final" );

		SimpleMultiThreading.threadHaltUnClean();
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

			final SolveItem right = new SolveItem( ( set0.minZ() + set0.maxZ() ) / 2, ( set1.minZ() + set1.maxZ() ) / 2, runParams );
			rightSets.add( right );

			set0.addOverlappingSolveItem( right );
			set1.addOverlappingSolveItem( right );

			right.addOverlappingSolveItem( set0 );
			right.addOverlappingSolveItem( set1 );
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
