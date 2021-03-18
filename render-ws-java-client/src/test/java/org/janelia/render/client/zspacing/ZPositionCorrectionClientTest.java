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

        final String userHome = System.getProperty("user.home");
        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z0720_07m_BR",
                "--project", "Sec39",
                "--stack", "v1_acquire_trimmed_sp1",
                "--scale", "0.25",
                "--minZ", "19950",
                "--maxZ", "20050",
//                "--minX", "2500",
//                "--maxX", "5500",
//                "--minY", "400",
//                "--maxY", "1700",
//                "--debugFormat", "png",
//                "--optionsJson", userHome + "/Desktop/inference-options.json",
                "--rootDirectory", userHome + "/Desktop/zcorr"
        };

        ZPositionCorrectionClient.main(effectiveArgs);
    }
    
}
