package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportTransformChangesClient} class.
 *
 * @author Eric Trautman
 */
public class ImportTransformChangesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportTransformChangesClient.Parameters());
    }

}
