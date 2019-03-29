package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TransformRemovalClient} class.
 *
 * @author Eric Trautman
 */
public class TransformRemovalClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TransformRemovalClient.Parameters());
    }

}
