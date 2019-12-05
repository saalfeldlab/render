package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link RedistributeTilePairsClient} class.
 *
 * @author Eric Trautman
 */
public class RedistributeTilePairsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RedistributeTilePairsClient.Parameters());
    }

}
