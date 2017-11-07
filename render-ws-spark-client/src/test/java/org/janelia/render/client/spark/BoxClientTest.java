package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link BoxClient} class.
 *
 * @author Eric Trautman
 */
public class BoxClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new BoxClient.Parameters());
    }

}
