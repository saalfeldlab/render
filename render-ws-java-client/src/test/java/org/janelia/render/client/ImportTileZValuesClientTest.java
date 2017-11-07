package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportTileZValuesClient} class.
 *
 * @author Eric Trautman
 */
public class ImportTileZValuesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportTileZValuesClient.Parameters());
    }

}
