package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link FixAutoLoaderScaleClient} class.
 *
 * @author Eric Trautman
 */
public class FixAutoLoaderScaleClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new FixAutoLoaderScaleClient.Parameters());
    }

}
