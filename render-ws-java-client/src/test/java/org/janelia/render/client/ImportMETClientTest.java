package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportMETClient} class.
 *
 * @author Eric Trautman
 */
public class ImportMETClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportMETClient.Parameters());
    }

}
