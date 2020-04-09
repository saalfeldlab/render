package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link PatchTileSpecClient} class.
 *
 * @author Eric Trautman
 */
public class PatchTileSpecClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new PatchTileSpecClient.Parameters());
    }

}
