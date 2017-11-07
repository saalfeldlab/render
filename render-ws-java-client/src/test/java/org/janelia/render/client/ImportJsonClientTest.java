package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportJsonClient} class.
 *
 * @author Eric Trautman
 */
public class ImportJsonClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportJsonClient.Parameters());
    }

}
