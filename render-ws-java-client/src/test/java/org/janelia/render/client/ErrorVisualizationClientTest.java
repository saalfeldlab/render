package org.janelia.render.client;

import org.janelia.render.client.ErrorVisualizationClient.Parameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ExampleMatchVisualizationClient} class.
 */
public class ErrorVisualizationClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            runTestWithExternalDependencies();
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private static void runTestWithExternalDependencies()
            throws Exception {

        final String[] args = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z1217_19m",

                "--project", "Sec09",
                "--stack", "v1_py_solve_03_affine_e10_e10",
                "--matchCollection", "gd_test_Sec09"

                //"--project", "Sec08",
                //"--stack", "v2_py_solve_03_affine_e10_e10",
                //"--matchCollection", "gd_test_Sec08"
                
                //"--project", "Sec07",
                //"--stack", "affine_pm_test_matlab_2",
                //"--matchCollection", "gd_test_3_Sec07_v1"

                //"--minZ", "36800",
                //"--maxZ", "37010",
        };

        // http://renderer-dev.int.janelia.org:8080/render-ws/view/index.html?dynamicRenderHost=renderer-dev%3A8080&catmaidHost=renderer-catmaid%3A8000&renderStackOwner=Z1217_19m&matchOwner=Z1217_19m&renderStackProject=Sec07&renderStack=v1_acquire_pre_align&matchCollection=gd_test_3_Sec07_v1
        
        final Parameters parameters = new Parameters();
        parameters.parse(args);

        final ErrorVisualizationClient client = new ErrorVisualizationClient(parameters);

        client.printConnections();
    }

}
