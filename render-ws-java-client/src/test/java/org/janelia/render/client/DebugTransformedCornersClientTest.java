package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link DebugTransformedCornersClient} class.
 *
 * @author Eric Trautman
 */
public class DebugTransformedCornersClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new DebugTransformedCornersClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",

                // include multiple stacks to compare
                "--stack", "v1_acquire_slab_001",
//                "--stack", "v1_acquire_slab_001_trimmed_align",

                "--xyNeighborFactor", "0.6", // for tiles completely within mFov
//                "--xyNeighborFactor", "0.3", // for tiles that overlap another mFov

//                "--zNeighborDistance", "0",
                "--zNeighborDistance", "1",

//                "--tileId", "001_000004_004_20220401_172116.1225.0", // very similar stage and align results
//                "--tileId", "001_000003_070_20220401_172114.1225.0", // slight differences between stage and align

                "--tileIdPattern", "001_000003_070.*",
                "--maxZ", "1227",
        };

        DebugTransformedCornersClient.main(effectiveArgs);

    }
}
