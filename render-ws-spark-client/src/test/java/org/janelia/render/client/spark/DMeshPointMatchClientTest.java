package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link DMeshPointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class DMeshPointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new DMeshPointMatchClient.Parameters());
    }

}
