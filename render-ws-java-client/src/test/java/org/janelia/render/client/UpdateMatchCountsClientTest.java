package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link UpdateMatchCountsClient} class.
 *
 * @author Eric Trautman
 */
public class UpdateMatchCountsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UpdateMatchCountsClient.Parameters());
    }

    public static void main(final String[] args) {
        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "test",
                "--collection", "Beautification_pm",
                "--jobIndex", "0",
                "--totalNumberOfJobs", "4",
        };
        UpdateMatchCountsClient.main(effectiveArgs);
    }
}
