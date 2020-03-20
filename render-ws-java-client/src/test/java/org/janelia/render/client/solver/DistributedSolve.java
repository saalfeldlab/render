package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.render.client.ClientRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mpicbg.models.Affine2D;
import mpicbg.models.Model;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

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

		final Pair< List< Pair< Integer, Integer > >, List< Pair< Integer, Integer > > > sets = defineSets( minZ, maxZ, setSize );
		
		printSets( sets );
	}

	protected static Pair< List< Pair< Integer, Integer > >, List< Pair< Integer, Integer > > > defineSets( final int minZ, final int maxZ, final int setSize )
	{
		final int modulo = ( maxZ - minZ + 1 ) % setSize;

		final int numSetsLeft = ( maxZ - minZ + 1 ) / setSize + Math.min( 1, modulo );

		final ArrayList< Pair< Integer, Integer > > leftSets = new ArrayList<>();
		final ArrayList< Pair< Integer, Integer > > rightSets = new ArrayList<>();

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			leftSets.add( new ValuePair<>( minZ + i * setSize, Math.min( minZ + (i + 1) * setSize - 1, maxZ ) ) );
		}

		for ( int i = 0; i < numSetsLeft - 1; ++i )
		{
			final Pair< Integer, Integer > set0 = leftSets.get( i );
			final Pair< Integer, Integer > set1 = leftSets.get( i + 1 );

			rightSets.add( new ValuePair<>( ( set0.getA() + set0.getB() ) / 2, ( set1.getA() + set1.getB() ) / 2 ) );
		}

		return new ValuePair<>( leftSets, rightSets );
	}

	protected static void printSets( final Pair< List< Pair< Integer, Integer > >, List< Pair< Integer, Integer > > > sets )
	{
		final int numSetsLeft = sets.getA().size();

		LOG.info( "Defined sets for global solve" );

		for ( int i = 0; i < numSetsLeft; ++i )
		{
			LOG.info( sets.getA().get( i ).getA() + " >> " + sets.getA().get( i ).getB() );

			if ( i < numSetsLeft - 1 )
				LOG.info( "\t" + sets.getB().get( i ).getA() + " >> " + sets.getB().get( i ).getB() );
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
                            "--maxZ", "11111",

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
