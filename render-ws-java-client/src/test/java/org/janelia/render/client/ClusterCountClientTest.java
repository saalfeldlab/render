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
                "--owner", "trautmane",
                "--project", "w60_serial_290_to_299",
                "--stack", "w60_s296_r00_d00_google_cluster0",
                "--matchCollection", "w60_s296_r00_d00_google_cluster0_match",
                "--maxSmallClusterSize", "0",
                "--includeMatchesOutsideGroup",
                "--maxLayersPerBatch", "1000",
                "--maxOverlapLayers", "6"
        };

        ClusterCountClient.main(effectiveArgs);
    }
}
