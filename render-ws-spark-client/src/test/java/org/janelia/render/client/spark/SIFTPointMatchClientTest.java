package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link SIFTPointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class SIFTPointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new SIFTPointMatchClient.Parameters());
    }

}
