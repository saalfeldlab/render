package org.janelia.render.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link HackLabelEdgesClient} class.
 *
 * @author Eric Trautman
 */
public class HackLabelEdgesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new HackLabelEdgesClient.Parameters());
    }

    public static void main(final String[] args) {

        final Path edgeCsvPath = Paths.get("/Users/trautmane/Desktop/edge.csv");

        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_tps_20181219",
                "--width", "1000",
                "--height", "1000",
                "--rootDirectory", "/Users/trautmane/Desktop",
                "--replaceExistingLabel", "true",
                "--edgeCsvFile", edgeCsvPath.toString()
        };

        final HackLabelEdgesClient.Parameters clientParameters = new HackLabelEdgesClient.Parameters();
        clientParameters.parse(effectiveArgs);

        final HackLabelEdgesClient client = new HackLabelEdgesClient(clientParameters);
        try {
            Files.write(edgeCsvPath, "2408,150504171631052111.2408.0,TOP".getBytes());
            client.generateHackedLabels(false);
        } catch (final Throwable t) {
            t.printStackTrace();
        }

    }
}
