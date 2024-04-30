package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link PreAlignClient} class.
 *
 * @author Michael Innerberger
 */
public class PreAlignClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new PreAlignClient.Parameters());
    }

    public static void main(final String[] args) {
        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
                "--stack", "c009_s310_v01_mfov_08",
                "--targetStack", "c009_s310_v01_mfov_08_mfov_prealign",
                "--matchOwner", "hess_wafer_53",
                "--matchCollection", "c009_s310_v01_match",
                "--completeTargetStack",
                "--maxAllowedError", "10.0",
                "--maxIterations", "1000",
                "--maxPlateauWidth", "250",
                "--maxNumMatches", "1000",
                "--numThreads", "8"
        };

        PreAlignClient.main(effectiveArgs);
    }
}
