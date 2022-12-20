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
import mpicbg.models.Affine2D;
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
								Double.NaN, // maxRange
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
                            "--owner", "hess", //"Z0720_07m_BR", //"flyem", //"cosem", //"Z1217_33m_BR",
                            "--project", "wafer_52c", //"Sec24", //"Z0419_25_Alpha3", //"jrc_hela_2", //"Sec10",
                            "--matchCollection", "wafer_52c_v1", //"Sec24_v1", //"Sec32_v1", //"Z0419_25_Alpha3_v1", //"jrc_hela_2_v1", //"Sec10_multi",

//                            "--stack", "v1_acquire_slab_001_trimmed_mfov_04",
//                            "--targetStack", "mfov_04_one_z_align",
//                            "--minZ", "1234",
//                            "--maxZ", "1234",
//                            // errors: 0.8330135514585507/0.9516363433105897/1.1195642054611685

//                            "--stack", "v1_acquire_slab_001_trimmed_mfov_04",
//                            "--targetStack", "mfov_04_five_z_align",
//                            "--minZ", "1225",
//                            "--maxZ", "1229",
//                            // errors: 1.9155474145898972/2.5098725148168426/3.16740645632977

//                            "--stack", "v1_acquire_slab_001_trimmed_mfov_02_04_05",
//                            "--targetStack", "mfov_02_04_05_one_z_align",
//                            "--minZ", "1234",
//                            "--maxZ", "1234",
//                            // errors: 0.8219440784842432/0.976720860591072/1.2759657838368308

//                            "--stack", "v1_acquire_slab_001_trimmed_mfov_02_04_05",
//                            "--targetStack", "mfov_02_04_05_five_z_align",
//                            "--minZ", "1225",
//                            "--maxZ", "1229",
//                            // errors: 1.915277838122907/2.5380865464826816/3.321787529996772 (took 4 minutes)

//                            "--completeTargetStack",
                            "--visualizeResults",

                            //"--noreg","400, 23434, 23-254",

                            // note: prealign is with translation only
                            "--blockOptimizerLambdasRigid",       "1.0,1.0,0.9,0.3,0.01",
                            "--blockOptimizerLambdasTranslation", "1.0,0.0,0.0,0.0,0.0",
                            "--blockOptimizerIterations", "100,100,50,25,25",
                            "--blockMaxPlateauWidth", "25,25,15,10,10",
                            //"--blockOptimizerIterations", "1000,1000,500,250,250",
                            //"--blockMaxPlateauWidth", "250,250,150,100,100",

                            //"--blockSize", "100",
                            "--minStitchingInliers", "100000000",// do not stitch first
                            "--maxNumMatches", "0", // no limit, default
                            "--threadsWorker", "1", 
                            "--threadsGlobal", "60",
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
						new SolveSetFactoryAdaptiveRigid(
								parameters.globalModel(),
								parameters.blockModel(),
								parameters.stitchingModel(),
								parameters.blockOptimizerLambdasRigid,
								parameters.blockOptimizerLambdasTranslation,
								parameters.blockOptimizerIterations,
								parameters.blockMaxPlateauWidth,
								parameters.minStitchingInliers,
								parameters.blockMaxAllowedError,
								parameters.dynamicLambdaFactor);

				final DistributedSolve solve =
						new DistributedSolveMultiThread(
								solveSetFactory,
								parameters);

				solve.run();

				if (parameters.visualizeResults) {

					final GlobalSolve gs = solve.globalSolve();

					// visualize the layers
					final HashMap<String, Float> idToValue = new HashMap<>();
					for (final String tileId : gs.idToTileSpecGlobal.keySet())
						idToValue.put(tileId,
									  gs.zToDynamicLambdaGlobal.get((int) Math.round(gs.idToTileSpecGlobal.get(tileId).getZ())).floatValue() +
									  1); // between 1 and 1.2

					BdvStackSource<?> vis =
							VisualizeTools.visualizeMultiRes(gs.idToFinalModelGlobal, gs.idToTileSpecGlobal, idToValue, 1, 128, 2, parameters.threadsGlobal);

					if (parameters.targetStack == null)
						LOG.info("Cannot render actual stack because it wasn't saved ( parameters.targetStack == null)");
					else
						vis = RenderTools.renderMultiRes(
								null,
								parameters.renderWeb.baseDataUrl,
								parameters.renderWeb.owner,
								parameters.renderWeb.project,
								parameters.targetStack,
								gs.idToFinalModelGlobal,
								gs.idToTileSpecGlobal,
								vis, 36);
					vis.setDisplayRange(0, 255);
					vis.setCurrent();


					SimpleMultiThreading.threadHaltUnClean();
				}
			}
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveMultiThread.class);

}
