package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TransformSectionClient} class.
 *
 * @author Eric Trautman
 */
public class TransformSectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TransformSectionClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_20190309",
                "--targetStack", "v15_montage_20190309_move",
                "--transformId", "MOVE",
                "--transformClass", "mpicbg.trakem2.transform.AffineModel2D",
                "--transformData", "1,0,0,1,-4800,-5000",
                "--transformApplicationMethod", "PRE_CONCATENATE_LAST",
                //"--layerMinimumXAndYBound", "10",
                "--completeTargetStack",
                "27", "315"
        };
        TransformSectionClient.main(effectiveArgs);
    }

}
