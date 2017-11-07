package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link FixMipmapUrlClient} class.
 *
 * @author Eric Trautman
 */
public class FixMipmapUrlClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new FixMipmapUrlClient.Parameters());
    }

}
