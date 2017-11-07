package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link StorePotentialTilePairsClient} class.
 *
 * @author Eric Trautman
 */
public class StorePotentialTilePairsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new StorePotentialTilePairsClient.Parameters());
    }

}
