package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link BoxRemovalClient} class.
 *
 * @author Eric Trautman
 */
public class BoxRemovalClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new BoxRemovalClient.Parameters());
    }

}
