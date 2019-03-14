package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link SplitClusterClient} class.
 *
 * @author Eric Trautman
 */
public class SplitClusterClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new SplitClusterClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://10.40.3.162:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_tps_20181219",
                "--maxClustersPerOriginalZ", "40",
                "--matchCollection", "FAFB_montage_fix",
                "--maxSmallClusterSize", "0",
                //"--renderIntersectingClusters", // will only work if source images and masks are local
                //"--renderClustersMaxCount", "8", // will only work if source images and masks are local
                //"--rootOutputDirectory", "/Users/trautmane/Desktop",
                "--z", "27.0"
        };
        SplitClusterClient.main(effectiveArgs);
    }
    
}
