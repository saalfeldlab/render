package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportDmgClient} class.
 *
 * @author Eric Trautman
 */
public class ImportDmgClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportDmgClient.Parameters());
    }

}
