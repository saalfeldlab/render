package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVMatchClient} class.
 *
 * @author Eric Trautman
 */
public class MFOVMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVMatchClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess",
                "--project", "wafer_52c",
                "--stack", "v1_acquire_slab_001",
                "--xyNeighborFactor", "0.6", // for tiles completely within mFov
                "--stored_match_weight", "0.0001",

                "--matchCollection", "wafer_52c_v1",

                "--mfov", "001_000004",
                "--matchStorageFile", "/tmp/001_000004_missing.json",
        };

        MFOVMatchClient.main(effectiveArgs);

    }
}
