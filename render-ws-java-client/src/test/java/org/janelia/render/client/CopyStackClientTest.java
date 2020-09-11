package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link CopyStackClient} class.
 *
 * @author Eric Trautman
 */
public class CopyStackClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new CopyStackClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage_wobble",
                "--fromStack", "v15_acquire_wobble",
                "--toStack", "v15_acquire_wobble_fix_1246",
                "--replaceLastTransformWithStage", "--completeToStackAfterCopy",
                "--excludedTileIds",
                "151019112742038010.1246.0", "151019112742036011.1246.0", "151019112742034013.1246.0",
                "151019112742028014.1246.0", "151019112742024015.1246.0", "151019112742042025.1246.0",
                "151019112742030026.1246.0", "151019112742028029.1246.0", "151019112742039046.1246.0",
                "151019112742035043.1246.0", "151019112742034044.1246.0", "151019112742034043.1246.0",
                "151019112742029034.1246.0", "151019112742025032.1246.0", "151019112742031036.1246.0",
                "151019112742034059.1246.0", "151019112742049022.1246.0",
                "--z", "1246"
        };

        CopyStackClient.main(effectiveArgs);
    }

}
