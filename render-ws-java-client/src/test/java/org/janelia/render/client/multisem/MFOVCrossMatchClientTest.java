package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVCrossMatchClient} class.
 *
 * @author Eric Trautman
 */
public class MFOVCrossMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVCrossMatchClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",
                "--stack", "v1_acquire_slab_001_align_w0p1",
//                "--stored_match_weight", "0.1",
                "--stored_match_weight", "0.9",
                "--stageJson", "/Users/trautmane/Desktop/mfov/stage_parameters.mfov_cross.json",

                "--matchCollection", "wafer_52c_v2",

                //"--mfov", "001_000001",
                //"--matchStorageFile", "/Users/trautmane/Desktop/mfov/missing_cross_matches.json",
                "--z", "1249"

                
        };

        MFOVCrossMatchClient.main(effectiveArgs);

    }
}
