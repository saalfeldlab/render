package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TileReorderingClient} class.
 *
 * @author Michael Innerberger
 */
public class TileReorderingClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TileReorderingClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = args.length > 0 ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53d",
                "--project", "slab_120_to_129",
                "--stack", "s127_m232_align_mi_ic_test1",
                "--targetStack", "s127_m232_align_mi_reordering_test_horizontal_scan"
        };

        TileReorderingClient.main(effectiveArgs);
    }
}
