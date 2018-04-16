package org.janelia.render.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.janelia.render.client.parameter.CommandLineParameters;
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

    // These tests require access to remote data - adjust data references as needed to run the tests locally.
    public static void main(final String[] args) {

        final String rootDirectory = "/Users/trautmane/Desktop/feature_test_data";

        try {
            testPairs(rootDirectory, 1, 1);
            testClippedPairs(rootDirectory);
        } catch (final Throwable t) {
            LOG.error("caught exception", t);
        }
    }

    private static void testPairs(final String rootDirectory,
                                  final int beginIndex,
                                  final int endIndex) throws Exception {

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
                "--rootFeatureDirectory", rootDirectory,
                "--beginIndex", String.valueOf(beginIndex),
                "--endIndex", String.valueOf(endIndex),
                "--pairJson", pairsPath.toString()
        };

        runWithArgs(args);
    }

    private static void testClippedPairs(final String rootDirectory) throws Exception {

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
                "--rootFeatureDirectory", rootDirectory,
                "--clipWidth", "500",
                "--pairJson", pairsPath.toString()
        };

        runWithArgs(clipArgs);
    }

    private static void runWithArgs(final String[] args) throws Exception {

        final FeatureClient.Parameters parameters = new FeatureClient.Parameters();
        parameters.parse(args);

        LOG.info("runWithArgs: entry, parameters={}", parameters);

        final FeatureClient featureClient = new FeatureClient(parameters);
        featureClient.run();

        LOG.info("runWithArgs: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(FeatureClientTest.class);
}
