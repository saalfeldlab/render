package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;

import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImageJ;
import mpicbg.models.Affine2D;
import mpicbg.models.Model;

public class DistributedSolve< B extends Model< B > & Affine2D< B > >
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

		final DistributedSolveWorker< B > l = new DistributedSolveWorker< B >( parameters, solveSet.leftItems.get( 0 ) );

		try
		{
			l.run();
	
			new ImageJ();
			l.getSolveItem().visualizeInput();
			l.getSolveItem().visualizeAligned();
		}
		catch ( Exception e )
		{
			e.printStackTrace();
		}
	}

	protected static void createSets()
	{
		
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

		return new SolveSet( leftSets, rightSets );
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
                            "--maxZ", "10311",

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
