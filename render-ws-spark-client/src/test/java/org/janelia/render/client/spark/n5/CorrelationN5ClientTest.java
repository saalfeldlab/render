package org.janelia.render.client.spark.n5;

import java.io.IOException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link CorrelationN5Client} class.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("SameParameterValue")
public class CorrelationN5ClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new CorrelationN5Client.Parameters());
    }

    public static void main(final String[] args) {

        final String[] testArgs = {
                "--baseDataUrl", "http://em-services-1.int.janelia.org:8080/render-ws/v1",
                "--owner", "cellmap",
                "--project", "jrc_ut23_0590_001",
                "--stack", "v1_acquire_align",
                "--scale", "0.22",
                "--n5Path", "/nrs/cellmap/data/jrc_ut23-0590-001/jrc_ut23-0590-001.n5",
                "--regionSize", "1000",
                "--minZ", "40", "--maxZ", "50"
        };

        final int numberOfConcurrentTasks = 12;
        final String master = "local[" + numberOfConcurrentTasks + "]";
        final SparkConf sparkConf = new SparkConf()
                .setMaster(master)
                .setAppName(CorrelationN5ClientTest.class.getSimpleName());

        final CorrelationN5Client.Parameters parameters = new CorrelationN5Client.Parameters();
        parameters.parse(testArgs);
        parameters.validate();

        final CorrelationN5Client client = new CorrelationN5Client(parameters);

        try (final JavaSparkContext sparkContext = new JavaSparkContext(sparkConf)) {
            client.runWithContext(sparkContext);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
