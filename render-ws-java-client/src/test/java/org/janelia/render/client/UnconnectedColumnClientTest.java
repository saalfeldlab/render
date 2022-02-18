package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link UnconnectedColumnClient} class.
 *
 * @author Eric Trautman
 */
public class UnconnectedColumnClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UnconnectedColumnClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "Z0720_07m_VNC",
                "--project", "Sec11",
                "--stack", "v2_acquire_trimmed",
                "--minZ", "1",
                "--maxZ", "3000",
                "--matchCollection", "Sec11_v1",
                "--maxUnconnectedLayers", "20"
        };

        UnconnectedColumnClient.main(effectiveArgs);
    }
    
}
