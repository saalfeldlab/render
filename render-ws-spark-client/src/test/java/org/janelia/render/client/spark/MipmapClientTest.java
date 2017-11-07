package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MipmapClient} class.
 *
 * @author Eric Trautman
 */
public class MipmapClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MipmapClient.Parameters());
    }

}
