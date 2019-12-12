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
                "--owner", "Z1217_19m",
                "--project", "Sec07",
                "--stack", "v1_acquire",
                "--minZ", "150",
                "--maxZ", "300",
                "--matchCollection", "gd_test_3_Sec07_v1",
                "--maxSmallClusterSize", "0",
                "--includeMatchesOutsideGroup",
                "--maxLayersPerBatch", "1000",
                "--maxOverlapLayers", "6"
        };

        ClusterCountClient.main(effectiveArgs);
    }
    
}
