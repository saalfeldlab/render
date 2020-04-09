package org.janelia.render.client.spark;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MergeStacksClient} class.
 *
 * @author Eric Trautman
 */
public class MergeStacksClientTest {

    @Test
    public void testParameterParsing() {
        CommandLineParameters.parseHelp(new MergeStacksClient.Parameters());
    }

}
