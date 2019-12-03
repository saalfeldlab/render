package org.janelia.render.client;

import org.janelia.render.client.ExampleMatchVisualizationClient.Parameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ExampleMatchVisualizationClient} class.
 */
public class ExampleMatchVisualizationClientTest {

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
                "--project", "Sec07",
                "--stack", "v1_acquire",
                "--minZ", "1",
                "--maxZ", "10", // 37011
                "--sameLayerNeighborFactor", "0.6",
                "--crossLayerNeighborFactor", "0.1",
                "--matchCollection", "gd_test_2_Sec07_v1"
        };

        final Parameters parameters = new Parameters();
        parameters.parse(args);

        final ExampleMatchVisualizationClient client = new ExampleMatchVisualizationClient(parameters);

        client.printConnections();
    }

}
