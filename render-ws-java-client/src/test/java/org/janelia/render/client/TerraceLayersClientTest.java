package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TerraceLayersClient} class.
 *
 * @author Eric Trautman
 */
public class TerraceLayersClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TerraceLayersClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "check_923_split_rough",
                "--targetStack", "check_923_split_rough_terraced",
//                "--transformApplicationMethod", "PRE_CONCATENATE_LAST",
                "--roughMatchCollection", "FAFB_montage_check_923_split_py_solve_tier_0_0001x0001_000000",
                "--completeTargetStack"
        };
        TerraceLayersClient.main(effectiveArgs);
    }

}
