package org.janelia.render.client.spark.intensityadjust;

import java.io.IOException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.newsolver.setup.IntensityCorrectionSetup;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link IntensityCorrectionClient} class.
 *
 * @author Eric Trautman
 */
public class IntensityCorrectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new IntensityCorrectionSetup());
    }

    // These tests require access to remote data - adjust data references as needed to run the tests locally.
    public static void main(final String[] args) {

        final String[] testArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "cellmap",
                "--project", "jrc_mus_thymus_1",
                "--stack", "v2_acquire_align",
                "--targetStack", "v2_acquire_test_intensity_ett1",
                "--threadsWorker", "12",
                "--blockSizeZ", "3",
                "--completeTargetStack",
//                "--zDistanceJson", "/Users/trautmane/Desktop/z-dist.json",
                "--zDistance", "1", "--minZ", "1000", "--maxZ", "1002"
        };

        final int numberOfConcurrentTasks = 1; // note: because of small block size it is better to set this to 1
        final String master = "local[" + numberOfConcurrentTasks + "]";
        final SparkConf sparkConf = new SparkConf()
                .setMaster(master)
                .setAppName(IntensityCorrectionClientTest.class.getSimpleName());

        final IntensityCorrectionSetup cmdLineSetup = new IntensityCorrectionSetup();
        cmdLineSetup.parse(testArgs);

        final IntensityCorrectionClient client = new IntensityCorrectionClient(cmdLineSetup);

        try (final JavaSparkContext sparkContext = new JavaSparkContext(sparkConf)) {
            client.runWithContext(sparkContext);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

    }

}
