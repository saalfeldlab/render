package org.janelia.render.client.solve;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.alignment.match.ModelType;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import mpicbg.models.NoninvertibleModelException;

public class DistributedSolve
{
	public DistributedSolve( final Parameters parameters ) throws IOException
	{
		//super( parameters );
	}


	protected void run() throws IOException, ExecutionException,
			InterruptedException, NoninvertibleModelException
	{
		// TODO Auto-generated method stub
		
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
                            "--minZ", "15718",//"24700",
                            "--maxZ", "15810",//"26650",

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

                //final PartialSolveBoxed client = new PartialSolveBoxed(parameters);

                //client.run();
            }
        };
        clientRunner.run();
	}

	private static final Logger LOG = LoggerFactory.getLogger(SolveTools.class);

}
