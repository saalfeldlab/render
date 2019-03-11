package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link WarpTransformClient} class.
 *
 * @author Eric Trautman
 */
public class WarpTransformClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new WarpTransformClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://10.40.3.162:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_20190309",
                "--alignProject", "FAFB00",
                "--alignStack", "v14_align_tps_20170818",
                "--targetProject", "FAFB_montage",
                "--targetStack", "v15_montage_check_323_tps",
                "--validatorClass", "org.janelia.alignment.spec.validator.WarpedTileSpecValidator",
                "--validatorData", "maxDeltaThreshold:1000,warnDeltaThreshold:400,samplesPerDimension:16",
                "--matchCollection", "FAFB_montage_fix",
                "--maxSmallClusterSize", "0",
                "--completeTargetStack",
                "323"
        };
        WarpTransformClient.main(effectiveArgs);
    }
}
