package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link UpdateMaskUrlClient} class.
 *
 * @author Eric Trautman
 */
public class UpdateMaskUrlClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new UpdateMaskUrlClient.Parameters());
    }

}
