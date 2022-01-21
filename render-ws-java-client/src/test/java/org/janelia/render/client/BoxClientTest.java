package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link BoxClient} class.
 *
 * @author Eric Trautman
 */
public class BoxClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new BoxClient.Parameters());
    }

    public static void main(final String[] args) {
        final String zValues = "1";
        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "trautmane",
                "--project", "comet_rgb",
                "--stack", "comet_rgb_112",
                "--rootDirectory", "/Users/trautmane/Desktop/boxes",
                "--width", "200",
                "--height", "200",
                "--maxLevel", "0",
                "--format", "png",
                "--convertToGray", "false",
                zValues
        };
        BoxClient.main(effectiveArgs);
    }
}
