package org.janelia.render.client.solver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.solver.visualize.ErrorTools;
import org.janelia.render.client.solver.visualize.ErrorTools.ErrorFilter;
import org.janelia.render.client.solver.visualize.ErrorTools.ErrorType;
import org.janelia.render.client.solver.visualize.ErrorTools.Errors;
import org.janelia.render.client.solver.visualize.RenderTools;
import org.janelia.render.client.solver.visualize.VisualizeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.BdvStackSource;
import mpicbg.models.Affine2D;
import net.imglib2.multithreading.SimpleMultiThreading;

public class DistributedSolveDeSerialize extends DistributedSolve
{
	final File path;

	public DistributedSolveDeSerialize(
			final SolveSetFactory solveSetFactory,
			final DistributedSolveParameters parameters ) throws IOException
	{
		super( solveSetFactory, parameters );

		this.path = new File( parameters.serializerDirectory );

		if ( !this.path.exists() )
			throw new IOException( "Path '" + this.path.getAbsoluteFile() + "' does not exist." );

		// we do not want to serialize here
		this.serializer = null;
	}

	@SuppressWarnings("unchecked")
	@Override
	public List< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > distributedSolve()
	{
		final long time = System.currentTimeMillis();

		final ArrayList< SolveItemData< ? extends Affine2D< ? >, ? extends Affine2D< ? >, ? extends Affine2D< ? > > > allItems = new ArrayList<>();

		final String[] files = path.list((dir, name) -> name.endsWith(".obj"));

		Arrays.sort( files );

		LOG.info("Found " + files.length + " serialized objects" );

		if ( files.length < 3 )
		{
			LOG.info("Not sufficient, stopping." );
			System.exit( 0 );
		}

		for ( final String filename : files ) {
			try {
				 // Reading the object from a file 
	            final FileInputStream file = new FileInputStream(new File(path, filename ) );
	            final ObjectInputStream in = new ObjectInputStream(file);
	              
	            // Method for deserialization of object 
	            final SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>> solveItem = (SolveItemData<? extends Affine2D<?>, ? extends Affine2D<?>, ? extends Affine2D<?>>)in.readObject();

	            allItems.add( solveItem );

	            in.close(); 
	            file.close(); 
	              
	            System.out.println("Object has been deserialized " + solveItem.getId() );
	        } catch(final Exception e) {
				LOG.error("Failed to deserialize: " + filename, e);
				System.exit( 0 );
			}
		}

		LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

		return allItems;
	}

	public static void main(final String[] args)
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
                            //"--targetStack", "v1_acquire_sp_nodyn_v2",
                            //"--completeTargetStack",

                            "--minZ", "1",
                            "--maxZ", "9505", //"6480",//"34022",

                            //"--threadsWorker", "1", 
                            "--threadsGlobal", "65",
                            "--maxPlateauWidthGlobal", "50",
                            "--maxIterationsGlobal", "10000",
                            "--serializerDirectory", "."//"/groups/flyem/data/sema/spark_example/ser-0.3new"//"/groups/scicompsoft/home/preibischs/Documents/FIB-SEM/ser"//500_full"
                    };
                    parameters.parse(testArgs);
                } else {
                    parameters.parse(args);
                }

                LOG.info("runClient: entry, parameters={}", parameters);
               
                DistributedSolve.visualizeOutput = false;
                DistributedSolve.visMinZ = 3500;
                DistributedSolve.visMaxZ = 5000;
                
                final SolveSetFactory solveSetFactory =
        		new SolveSetFactorySimple(
        				parameters.globalModel(),
        				parameters.blockModel(),
        				parameters.stitchingModel(),
        				parameters.blockOptimizerLambdasRigid,
        				parameters.blockOptimizerLambdasTranslation,
        				parameters.blockOptimizerIterations,
        				parameters.blockMaxPlateauWidth,
        				parameters.minStitchingInliers,
        				parameters.blockMaxAllowedError,
        				parameters.dynamicLambdaFactor );

                final DistributedSolve solve =
                		new DistributedSolveDeSerialize(
                				solveSetFactory,
                				parameters );
                
                solve.run();

                final GlobalSolve gs = solve.globalSolve();

				// visualize maxError
				final Errors errors = ErrorTools.computeErrors( gs.idToErrorMapGlobal, gs.idToTileSpecGlobal, ErrorFilter.CROSS_LAYER_ONLY );
				BdvStackSource<?> vis = ErrorTools.renderErrors( errors, gs.idToFinalModelGlobal, gs.idToTileSpecGlobal );

				vis = ErrorTools.renderPotentialProblemAreas( vis, errors, ErrorType.AVG, 4.0, gs.idToFinalModelGlobal, gs.idToTileSpecGlobal );

				vis = VisualizeTools.renderDynamicLambda( vis, gs.zToDynamicLambdaGlobal, gs.idToFinalModelGlobal, gs.idToTileSpecGlobal, parameters.dynamicLambdaFactor );

				final BdvStackSource<?> img = RenderTools.renderMultiRes(
						null,
						parameters.renderWeb.baseDataUrl,
						parameters.renderWeb.owner,
						parameters.renderWeb.project,
						"v1_acquire_sp_nodyn_v2",
						gs.idToFinalModelGlobal,
						gs.idToTileSpecGlobal,
						vis, 36 );

				SimpleMultiThreading.threadHaltUnClean();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveDeSerialize.class);
}
