package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link SectionUpdateClient} class.
 *
 * @author Eric Trautman
 */
public class SectionUpdateClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new SectionUpdateClient.Parameters());
    }

}
