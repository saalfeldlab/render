package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link AddMaskUrlClient} class.
 *
 * @author Eric Trautman
 */
public class AddMaskUrlClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new AddMaskUrlClient.Parameters());
    }

}
