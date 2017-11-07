package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link WarpTransformClient} class.
 *
 * @author Eric Trautman
 */
public class WarpTransformClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new WarpTransformClient.Parameters());
    }

}
