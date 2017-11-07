package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link PointMatchOptimizerClient} class.
 *
 * @author Eric Trautman
 */
public class PointMatchOptimizerClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new PointMatchOptimizerClient.Parameters());
    }

}
