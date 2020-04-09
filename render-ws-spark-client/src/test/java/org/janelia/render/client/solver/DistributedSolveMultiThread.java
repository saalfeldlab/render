package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;

public class DistributedSolveMultiThread< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends DistributedSolve< G, B, S >
{

	public DistributedSolveMultiThread(
			final G globalSolveModel,
			final B blockSolveModel,
			final S stitchingModel,
			final Parameters parameters ) throws IOException

	{
		super( globalSolveModel, blockSolveModel, stitchingModel, parameters );
	}

	@Override
	public void run( final int setSize )
	{
		final long time = System.currentTimeMillis();

		final int minZ = (int)Math.round( this.runParams.minZ );
		final int maxZ = (int)Math.round( this.runParams.maxZ );

		final SolveSet< G, B, S > solveSet = defineSolveSet( minZ, maxZ, setSize );

		LOG.info( "Defined sets for global solve" );
		LOG.info( "\n" + solveSet );

		/*
		final DistributedSolveWorker w = new DistributedSolveWorker( parameters, solveSet.leftItems.get( 0 ) );
		try
		{
			w.run();
			for ( final SolveItem s : (ArrayList< SolveItem >)w.getSolveItems() )
				s.visualizeAligned();

		} catch ( IOException | ExecutionException | InterruptedException
				| NoninvertibleModelException e1 )
		{
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		SimpleMultiThreading.threadHaltUnClean();
		System.exit( 0 );
		*/

		// Multithreaded for now (should be Spark for cluster-)

		// set up executor service
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( 8 );
		final ArrayList< Callable< List< SolveItemData< G, B, S > > > > tasks = new ArrayList<>();

		for ( final SolveItemData< G, B, S > solveItemData : solveSet.allItems() )
		{
			tasks.add( new Callable< List< SolveItemData< G, B, S > > >()
			{
				@Override
				public List< SolveItemData< G, B, S > > call() throws Exception
				{
					final DistributedSolveWorker< G, B, S > w = new DistributedSolveWorker<>(
							solveItemData,
							runParams.pGroupList,
							runParams.sectionIdToZMap,
							parameters.renderWeb.baseDataUrl,
							parameters.renderWeb.owner,
							parameters.renderWeb.project,
							parameters.matchOwner,
							parameters.matchCollection,
							parameters.stack );
					w.run();
	
					return w.getSolveItemDataList();
				}
			});
		}

		final ArrayList< SolveItemData< G, B, S > > allItems = new ArrayList<>();

		try
		{
			// invokeAll() returns when all tasks are complete
			final List< Future< List< SolveItemData< G, B, S > > > > futures = taskExecutor.invokeAll( tasks );

			for ( final Future< List< SolveItemData< G, B, S > > > future : futures )
				allItems.addAll( future.get() );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute alignments: " + e );
			e.printStackTrace();
			return;
		}

		taskExecutor.shutdown();

		try
		{
			final GlobalSolve gs = globalSolve( allItems );

			LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

			// visualize new result
			new ImageJ();
			ImagePlus imp1 = SolveTools.render( gs.idToFinalModelGlobal, gs.idToTileSpecGlobal, 0.15 );
			imp1.setTitle( "final" );
			SimpleMultiThreading.threadHaltUnClean();

		}
		catch ( NotEnoughDataPointsException | IllDefinedDataPointsException | InterruptedException | ExecutionException | NoninvertibleModelException e )
		{
			e.printStackTrace();
			return;
		}
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

                            "--threads", "1",
                            "--maxIterations", "10000",
                            "--completeTargetStack",
                            "--matchCollection", "Sec08_patch_matt"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final DistributedSolve< RigidModel2D, InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >, InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D > > solve =
                		new DistributedSolveMultiThread<>(
                				new RigidModel2D(),
                				new InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >( new AffineModel2D(), new RigidModel2D(), parameters.startLambda ),
                				new InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D >( new RigidModel2D(), new TranslationModel2D(), 0.25 ),
                				parameters );
                
                solve.run( 100 );
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveMultiThread.class);

}
