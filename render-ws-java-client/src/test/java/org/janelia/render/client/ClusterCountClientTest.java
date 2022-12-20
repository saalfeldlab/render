package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ClusterCountClient} class.
 *
 * @author Eric Trautman
 */
public class ClusterCountClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ClusterCountClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52",
                "--stack", "v1_acquire_slab_001",
//                "--minZ", "1225", // 1226
//                "--maxZ", "1241", // 1242
                "--matchCollection", "wafer_52_v2",
                "--maxSmallClusterSize", "0",
                "--includeMatchesOutsideGroup",
                "--maxLayersPerBatch", "1000",
                "--maxOverlapLayers", "6"
        };

        ClusterCountClient.main(effectiveArgs);
    }
}
