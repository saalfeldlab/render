package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportMatchClient} class.
 *
 * @author Eric Trautman
 */
public class ImportMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportMatchClient.Parameters());
    }

}
