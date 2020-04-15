package org.janelia.render.client.solver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public class DistributedSolveDeSerialize< G extends Model< G > & Affine2D< G >, B extends Model< B > & Affine2D< B >, S extends Model< S > & Affine2D< S > > extends DistributedSolve< G, B, S >
{
	final File path;

	public DistributedSolveDeSerialize(
			final G globalSolveModel,
			final B blockSolveModel,
			final S stitchingModel,
			final ParametersDistributedSolve parameters,
			final File path ) throws IOException

	{
		super( globalSolveModel, blockSolveModel, stitchingModel, parameters );

		this.path = path;
	}

	@SuppressWarnings("unchecked")
	@Override
	public List< SolveItemData< G, B, S > > distributedSolve()
	{
		final long time = System.currentTimeMillis();

		final ArrayList< SolveItemData< G, B, S > > allItems = new ArrayList<SolveItemData<G,B,S>>();

		String[] files = path.list( new FilenameFilter() {
			
			@Override
			public boolean accept(File dir, String name)
			{
				if ( name.endsWith(".obj") )
					return true;
				else
					return false;
			}
		});

		Arrays.sort( files );

		LOG.info("Found " + files.length + " serialized objects" );
		
		for ( final String filename : files )
		{
			try
	        {
				 // Reading the object from a file 
	            FileInputStream file = new FileInputStream( new File( filename ) ); 
	            ObjectInputStream in = new ObjectInputStream(file); 
	              
	            // Method for deserialization of object 
	            SolveItemData< G, B, S > solveItem = (SolveItemData< G, B, S >)in.readObject(); 

	            allItems.add( solveItem );

	            in.close(); 
	            file.close(); 
	              
	            System.out.println("Object has been deserialized " + solveItem.getId() );
	        }
			catch( Exception e )
			{
				e.printStackTrace();
				System.exit( 0 );
			}
		}

		LOG.info( "Took: " + ( System.currentTimeMillis() - time )/100 + " sec.");

		return allItems;
	}

	public static void main( String[] args )
	{
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final ParametersDistributedSolve parameters = new ParametersDistributedSolve();

                // TODO: remove testing hack ...
                if (args.length == 0) {
                    final String[] testArgs = {
                            "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                            "--owner", "Z1217_33m_BR",
                            "--project", "Sec10",
                            "--matchCollection", "Sec10_multi",
                            "--stack", "v2_acquire_merged",
                            //"--targetStack", "v2_acquire_merged_mpicbg_stitchfirst_fix_prealign",
                            //"--completeTargetStack",
                            
                            "--blockOptimizerLambdas", "1.0,0.5,0.1,0.01",
                            "--blockOptimizerIterations", "200,100,40,20",
                            "--blockMaxPlateauWidth", "50,50,40,20",

                            //"--blockSize", "100",
                            //"--noStitching", // do not stitch first
                            
                            "--minZ", "1",
                            "--maxZ", "34022",

                            //"--threadsLocal", "1", 
                            "--threadsGlobal", "65",
                            "--maxPlateauWidthGlobal", "500",
                            "--maxIterationsGlobal", "10000"
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
                DistributedSolve.visMinZ = 240;
                DistributedSolve.visMaxZ = 260;
                
                @SuppressWarnings({ "rawtypes", "unchecked" })
                final DistributedSolve solve =
                		new DistributedSolveDeSerialize(
                				parameters.globalModel(),
                				parameters.blockModel(),
                				parameters.stitchingModel(),
                				parameters,
                				new File(".") );
                
                solve.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(DistributedSolveDeSerialize.class);

}
