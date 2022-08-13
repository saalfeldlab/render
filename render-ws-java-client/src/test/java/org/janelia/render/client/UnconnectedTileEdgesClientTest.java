package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link UnconnectedTileEdgesClient} class.
 *
 * @author Eric Trautman
 */
public class UnconnectedTileEdgesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UnconnectedTileEdgesClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "cellmap",
                "--project", "jrc_mus_liv_zon_1",
                "--stack", "v1_acquire",
                "--minZ", "1",
                "--maxZ", "2000",
                "--matchCollection", "jrc_mus_liv_zon_1_v1",
                "--maxUnconnectedLayers", "50"
        };

        UnconnectedTileEdgesClient.main(effectiveArgs);
    }
    
}
