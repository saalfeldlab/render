package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.render.client.solver.visualize.VisualizeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvStackSource;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.Affine2D;
import mpicbg.models.Model;
import mpicbg.spim.io.IOFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;

public class DistributedSolveMultiThread extends DistributedSolve
{
	public DistributedSolveMultiThread(
			final SolveSetFactory solveSetFactory,
			final DistributedSolveParameters parameters ) throws IOException
	{
		super( solveSetFactory, parameters );
	}

	@Override
	public List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > distributedSolve()
	{
		final long time = System.currentTimeMillis();

		final ArrayList< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allItems;

		// set up executor service
		LOG.info( "Multithreading with thread num=" + parameters.threadsGlobal );

		final ExecutorService taskExecutor = Executors.newFixedThreadPool( parameters.threadsGlobal );
		final ArrayList< Callable< List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > > > tasks = new ArrayList<>();

		for ( final SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > solveItemData : this.solveSet.allItems() )
		{
			tasks.add( new Callable< List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > >()
			{
				@Override
				public List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > call() throws Exception
				{
					final DistributedSolveWorker< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > w = 
							solveItemData.createWorker(
								solveSet.getMaxId() + 1,
								runParams.pGroupList,
								runParams.sectionIdToZMap,
								parameters.renderWeb.baseDataUrl,
								parameters.renderWeb.owner,
								parameters.renderWeb.project,
								parameters.matchOwner,
								parameters.matchCollection,
								parameters.stack,
								parameters.maxNumMatches,
								parameters.serializeMatches,
								parameters.maxAllowedErrorStitching,
								parameters.maxIterationsStitching,
								parameters.maxPlateauWidthStitching,
								parameters.excludeSet(),
								parameters.threadsWorker );
					w.run();
	
					return w.getSolveItemDataList();
				}
			});
		}

		allItems = new ArrayList<>();			

		try
		{
			// invokeAll() returns when all tasks are complete
			final List< Future< List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > > > futures = taskExecutor.invokeAll( tasks );

			
			for ( final Future< List< ? extends SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > > future : futures )
				allItems.addAll( future.get() );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute alignments: " + e );
			e.printStackTrace();
			return null;
		}

		taskExecutor.shutdown();

		LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

		return allItems;
	}

	public static void main( String[] args )
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final DistributedSolveParameters parameters = new DistributedSolveParameters();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                            "--owner", "flyem", //"cosem", //"Z1217_33m_BR",
                            "--project", "Z0419_25_Alpha3", //"jrc_hela_2", //"Sec10",
                            "--matchCollection", "Z0419_25_Alpha3_v1", //"jrc_hela_2_v1", //"Sec10_multi",
                            "--stack", "v1_acquire", //"v3_acquire",
                            "--targetStack", "v1_acquire_sp_nodyn_t4636",
                            "--completeTargetStack",
                            
                            //"--noreg","400, 23434, 23-254",
                            
                            "--blockOptimizerLambdasRigid",       "1.0,1.0,0.9,0.3,0.01",
                            "--blockOptimizerLambdasTranslation", "1.0,0.0,0.0,0.0,0.0",
                            "--blockOptimizerIterations", "1000,1000,500,250,250",
                            "--blockMaxPlateauWidth", "250,250,150,100,100",

                            //"--blockSize", "100",
                            //"--noStitching", // do not stitch first
                            
                            "--minZ", "1",
                            "--maxZ", "9505", //"6480",//"34022",

                            "--maxNumMatches", "0", // no limit, default
                            "--threadsWorker", "1", 
                            "--threadsGlobal", "72",
                            "--maxPlateauWidthGlobal", "50",
                            "--maxIterationsGlobal", "10000",
							//"--serializerDirectory", ".",
							//"--serializeMatches",
							"--dynamicLambdaFactor", "0.0"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                /*
                final DistributedSolve< RigidModel2D, InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >, InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D > > solve =
                		new DistributedSolveMultiThread<>(
                				new RigidModel2D(),
                				new InterpolatedAffineModel2D< AffineModel2D, RigidModel2D >( new AffineModel2D(), new RigidModel2D(), parameters.startLambda ),
                				new InterpolatedAffineModel2D< RigidModel2D, TranslationModel2D >( new RigidModel2D(), new TranslationModel2D(), 0.25 ),
                				parameters );
                */
               
                DistributedSolve.visualizeOutput = false;
                //DistributedSolve.visMinZ = 1223;
                //DistributedSolve.visMaxZ = 1285;
                
                final SolveSetFactory solveSetFactory =
        		new SolveSetFactoryAso(
        				parameters.globalModel(),
        				parameters.blockModel(),
        				parameters.stitchingModel(),
        				parameters.blockOptimizerLambdasRigid,
        				parameters.blockOptimizerLambdasTranslation,
        				parameters.blockOptimizerIterations,
        				parameters.blockMaxPlateauWidth,
        				parameters.blockMaxAllowedError,
        				parameters.dynamicLambdaFactor );

                final DistributedSolve solve =
                		new DistributedSolveMultiThread(
                				solveSetFactory,
                				parameters );

                solve.run();

                final GlobalSolve gs = solve.globalSolve();

                // visualize the layers
				final HashMap<String, Float> idToValue = new HashMap<>();
				for ( final String tileId : gs.idToTileSpecGlobal.keySet() )
					idToValue.put( tileId, gs.zToDynamicLambdaGlobal.get( (int)Math.round( gs.idToTileSpecGlobal.get( tileId ).getZ() ) ).floatValue() + 1 ); // between 1 and 1.2

				BdvStackSource<?> vis = VisualizeTools.visualizeMultiRes( gs.idToFinalModelGlobal, gs.idToTileSpecGlobal, idToValue, 1, 128, 2, parameters.threadsGlobal );

				vis = RenderTools.renderMultiRes(
						null,
						parameters.renderWeb.baseDataUrl,
						parameters.renderWeb.owner,
						parameters.renderWeb.project,
						"v1_acquire_sp_nodyn_t4636",
						gs.idToFinalModelGlobal,
						gs.idToTileSpecGlobal,
						vis, 36 );

                SimpleMultiThreading.threadHaltUnClean();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveMultiThread.class);

}
