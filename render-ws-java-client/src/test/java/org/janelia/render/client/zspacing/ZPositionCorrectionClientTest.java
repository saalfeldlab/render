package org.janelia.render.client.zspacing;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ZPositionCorrectionClient} class.
 *
 * @author Eric Trautman
 */
public class ZPositionCorrectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ZPositionCorrectionClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z0720_07m_VNC",
                "--project", "Sec32",
                "--stack", "v1_acquire_trimmed_sp1",
                "--scale", "0.3",
                "--minZ", "4600.0",
                "--maxZ", "4699.0",
                "--minX", "10740",
                "--maxX", "14740",
                "--minY", "1900",
                "--maxY", "3200",
//                "--debugFormat", "png",
                "--rootDirectory", System.getProperty("user.home") + "/Desktop/zcorr"
        };

        ZPositionCorrectionClient.main(effectiveArgs);
    }
    
}
