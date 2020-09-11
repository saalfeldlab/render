package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link DeltaStackClient} class.
 *
 * @author Eric Trautman
 */
public class DeltaStackClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new DeltaStackClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage_wobble",
                "--fromStack", "v15_acquire_wobble",
                "--toStack", "v15_montage_wobble_removed_review",
                "--excludeTileIdsInStacks",
//                "v15_montage_wobble_batch_00_py_solve",
                "v15_montage_wobble_batch_01_py_solve_2",
//                "v15_montage_wobble_batch_02_py_solve",
//                "v15_montage_wobble_batch_03_py_solve",
//                "v15_montage_wobble_batch_04_py_solve",
//                "v15_montage_wobble_batch_05_py_solve",
                "--consolidateForReview",
                "--z", "2000",
                "--completeToStackAfterCopy"
        };

        DeltaStackClient.main(effectiveArgs);
    }

}
