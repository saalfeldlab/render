package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVCrossMatchPatchClient} class.
 *
 * @author Eric Trautman
 */
public class MFOVCrossMatchPatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVCrossMatchPatchClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",
                "--stack", "v1_acquire_001_000003_montage",
                "--stored_match_weight", "0.1",
                "--stageJson", "render-ws-java-client/src/test/resources/multisem/stage_parameters.mfov_cross_try2.json",

                "--matchCollection", "wafer_52c_v1_cross_patch",

                //"--matchStorageFile", "/Users/trautmane/Desktop/mfov/missing_cross_matches.json",

                "--sfov", "001_000003_001", "001_000003_067",
                // "--sfov", "001_000003_062", "001_000003_077",
                // "--sfov", "001_000003_067", "001_000003_072",
                "--z", "1248", "1249"

                
        };

        MFOVCrossMatchPatchClient.main(effectiveArgs);

    }
}
