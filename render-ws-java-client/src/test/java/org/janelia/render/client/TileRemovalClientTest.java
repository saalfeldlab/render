package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TileRemovalClient} class.
 *
 * @author Eric Trautman
 */
public class TileRemovalClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TileRemovalClient.Parameters());
    }

}
