package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ExponentialFitClient} class.
 *
 * @author Michael Innerberger
 */
public class ExponentialFitClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ExponentialFitClient.Parameters());
    }

    public static void main(final String[] args) {
        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53d",
                "--project", "slab_070_to_079",
                "--stack", "s070_m104_align",
                "--targetStack", "s070_m104_align_exp_test01",
                "--coefficientsFile", "./coefficients.csv",
                "--completeTargetStack",
        };

        ExponentialFitClient.main(effectiveArgs);
    }
}
