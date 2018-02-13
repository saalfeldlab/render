package org.janelia.render.client.spark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.spark.SparkConf;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link FeatureClient} class.
 *
 * @author Eric Trautman
 */
public class FeatureClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new FeatureClient.Parameters());
    }

    // This test is ignored by default because it requires access to remote data.
    // Comment out the Ignore annotation and adjust data references as needed to run the test locally.
    @Ignore
    public void testLocalRun() throws Exception {

        final String rootDirectory = "/Users/trautmane/Desktop/feature_test_data";
        final int numberOfConcurrentTasks = 3;
        final String master = "local[" + numberOfConcurrentTasks + "]";

        testPairs(rootDirectory, master);
        testClippedPairs(rootDirectory, master);
    }

    private void testPairs(final String rootDirectory,
                           final String master) throws Exception {

        final Path pairsPath = Paths.get(rootDirectory, "pairs.json");

        final String json =
                "{\n" +
                "  \"renderParametersUrlTemplate\" : \"{baseDataUrl}/owner/flyTEM/project/trautmane_test/stack/rough_tiles/tile/{id}/render-parameters\",\n" +
                "  \"neighborPairs\" : [ {\n" +
                "    \"p\" : {\n" +
                "      \"groupId\" : \"2.0\",\n" +
                "      \"id\" : \"151215054132012010.2.0\"\n" +
                "    },\n" +
                "    \"q\" : {\n" +
                "      \"groupId\" : \"3.0\",\n" +
                "      \"id\" : \"151215054802011011.3.0\"\n" +
                "    }\n" +
                "  }, {\n" +
                "    \"p\" : {\n" +
                "      \"groupId\" : \"2.0\",\n" +
                "      \"id\" : \"151215054132012010.2.0\"\n" +
                "    },\n" +
                "    \"q\" : {\n" +
                "      \"groupId\" : \"3.0\",\n" +
                "      \"id\" : \"151215054802012010.3.0\"\n" +
                "    }\n" +
                "  } ]\n" +
                "}";

        Files.write(pairsPath, json.getBytes());

        final String[] args = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--rootDirectory", rootDirectory,
                "--pairJson", pairsPath.toString()
        };

        runWithArgs(args, master);
    }

    private void testClippedPairs(final String rootDirectory,
                                  final String master) throws Exception {

        final Path pairsPath = Paths.get(rootDirectory, "pairs_clip.json");

        final String json =
                "{\n" +
                "  \"renderParametersUrlTemplate\" : \"{baseDataUrl}/owner/flyTEM/project/trautmane_test/stack/rough_tiles/tile/{id}/render-parameters\",\n" +
                "  \"neighborPairs\" : [ {\n" +
                "    \"p\" : {\n" +
                "      \"groupId\" : \"2.0\",\n" +
                "      \"id\" : \"151215054132012010.2.0\",\n" +
                "      \"relativePosition\" : \"LEFT\"\n" +
                "    },\n" +
                "    \"q\" : {\n" +
                "      \"groupId\" : \"3.0\",\n" +
                "      \"id\" : \"151215054802011011.3.0\",\n" +
                "      \"relativePosition\" : \"RIGHT\"\n" +
                "    }\n" +
                "  }, {\n" +
                "    \"p\" : {\n" +
                "      \"groupId\" : \"2.0\",\n" +
                "      \"id\" : \"151215054132012010.2.0\",\n" +
                "      \"relativePosition\" : \"LEFT\"\n" +
                "    },\n" +
                "    \"q\" : {\n" +
                "      \"groupId\" : \"3.0\",\n" +
                "      \"id\" : \"151215054802012010.3.0\",\n" +
                "      \"relativePosition\" : \"RIGHT\"\n" +
                "    }\n" +
                "  } ]\n" +
                "}";

        Files.write(pairsPath, json.getBytes());

        final String[] clipArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--rootDirectory", rootDirectory,
                "--clipWidth", "500",
                "--pairJson", pairsPath.toString()
        };

        runWithArgs(clipArgs, master);
    }

    private void runWithArgs(final String[] args,
                             final String master) throws Exception {

        final FeatureClient.Parameters parameters = new FeatureClient.Parameters();
        parameters.parse(args);

        LOG.info("runWithArgs: entry, parameters={}", parameters);

        final FeatureClient featureClient = new FeatureClient(parameters);

        final SparkConf sparkConf = new SparkConf().setMaster(master).setAppName(this.getClass().getSimpleName());
        featureClient.run(sparkConf);

        LOG.info("runWithArgs: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(FeatureClientTest.class);
}
