package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TranslateClustersClient} class.
 *
 * @author Eric Trautman
 */
public class TranslateClustersClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TranslateClustersClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://tem-services.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage_missed",
                "--stack", "v15_montage_20190719_py_solve2",
                "--targetStack", "test_terrace_cluster_3",
                "--matchCollection", "FAFB_montage_fix_missed",
                "--z", "1220",
                "--completeTargetStack"
        };
        TranslateClustersClient.main(effectiveArgs);
    }

}
