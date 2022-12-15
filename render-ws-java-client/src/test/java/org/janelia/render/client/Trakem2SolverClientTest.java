package org.janelia.render.client;

import mpicbg.models.TranslationModel2D;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link Trakem2SolverClient} class.
 *
 * @author Eric Trautman
 */
public class Trakem2SolverClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new Trakem2SolverClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following method supports ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        final String[] testArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",

                "--stack", "v1_acquire_001_000003",
                "--minZ", "1225",
                "--maxZ", "1225",

//                "--targetStack", "v1_acquire_001_000003_montage",
                "--regularizerModelType", "TRANSLATION",
//                "--optimizerLambdas", "1.0,0.5,0.1,0.01",
                "--optimizerLambdas", "0.1,0.01",
                "--maxIterations", "250,250",

                "--threads", "1",
                "--completeTargetStack",
                "--matchCollection", "wafer_52c_v2"
        };

        for (double z = 1225.0; z < 1251.0; z++) {
            testArgs[9] = String.valueOf(z);
            testArgs[11] = String.valueOf(z);
            final Trakem2SolverClient.Parameters parameters = new Trakem2SolverClient.Parameters();
            parameters.parse(testArgs);
            try {
                final Trakem2SolverClient<TranslationModel2D> client = new Trakem2SolverClient<>(parameters);
                client.run();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

}
