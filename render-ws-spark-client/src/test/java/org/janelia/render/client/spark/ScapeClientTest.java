package org.janelia.render.client.spark;

import org.apache.spark.SparkConf;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ScapeClient} class.
 *
 * @author Eric Trautman
 */
public class ScapeClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ScapeClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) throws Exception {

        final int numberOfConcurrentTasks = 1;
        final String master = "local[" + numberOfConcurrentTasks + "]";

        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z0720_07m_VNC",
                "--project", "Sec06",
                "--stack", "v1_acquire",
                "--resolutionUnit", "nm",
                "--rootDirectory", "/Users/trautmane/Desktop/scape_test",
                "--scale", "0.25",
                "--format", "tif",
                "--useLayerBounds", "true",
                "--minZ", "1",
                "--maxZ", "1",
        };

        final ScapeClient.Parameters parameters = new ScapeClient.Parameters();
        parameters.parse(effectiveArgs);

        final ScapeClient scapeClient = new ScapeClient(parameters);

        final SparkConf sparkConf = new SparkConf()
                .setMaster(master)
                .setAppName(ScapeClientTest.class.getSimpleName());
        scapeClient.run(sparkConf);
    }
}
