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
                "--unconnectedMFOVPairsFile", "/Users/trautmane/Desktop/mfov/unconnected_mfov_pairs.json",
                "--storedMatchWeight", "0.9",
                "--stageJson", "render-ws-java-client/src/test/resources/multisem/stage_parameters.mfov_cross.json",
                "--matchStorageFile", "/Users/trautmane/Desktop/mfov/missing_cross_matches.json",
                "--sfovIndex", "001", "067"
        };

        MFOVCrossMatchPatchClient.main(effectiveArgs);

    }
}
