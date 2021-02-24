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
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "Z0720_07m_VNC",
                "--project", "Sec32",
                "--stack", "v1_acquire_trimmed_sp1",
                "--scale", "0.125",
                "--minZ", "12201.0",
                "--maxZ", "12300.0",
                "--minX", "0",
                "--minY", "3600",
                "--maxX", "3600",
                "--maxY", "6100",
//                "--debugFormat", "tif",
                "--rootDirectory", "/Users/trautmane/Desktop/zcorr"
//                "--matchCollection", "gd_test_Sec08",
        };

        ZPositionCorrectionClient.main(effectiveArgs);
    }
    
}
