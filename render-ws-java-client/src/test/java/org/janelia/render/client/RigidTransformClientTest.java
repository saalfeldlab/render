package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link RigidTransformClient} class.
 *
 * @author Eric Trautman
 */
public class RigidTransformClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RigidTransformClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://10.40.3.162:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
//                "--stack", "v15_montage_20190901",
                "--stack", "v15_montage_20190503_try2",
                "--includeMatchesOutsideGroup",
                "--alignProject", "FAFB00",
                "--alignStack", "v14_align_tps_20170818",
                "--targetProject", "FAFB_montage",
                "--targetStack", "v15_montage_20190901_rigid_test",
                "--matchCollection", "FAFB_montage_fix",
                "--maxSmallClusterSize", "3",
                "--completeTargetStack",
//                "484", "1198", "1199", "1200", "3224", "5571",
                "271", "6704"
        };

        RigidTransformClient.main(effectiveArgs);
    }
}
