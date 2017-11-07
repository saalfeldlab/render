package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link TransformSectionClient} class.
 *
 * @author Eric Trautman
 */
public class TransformSectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new TransformSectionClient.Parameters());
    }

}
