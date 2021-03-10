package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ImportSlicesClient} class.
 *
 * @author Eric Trautman
 */
public class ImportSlicesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ImportSlicesClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = args.length > 0 ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "Z1217_19m",
                "--project", "slices",
                "--stack", "Sec08_matt_scale_0125_test",
                "--sliceListing", "/Users/trautmane/Desktop/zcorr/matt_Sec08/slice-file-list.txt",
                "--completeStackAfterImport"
        };

        ImportSlicesClient.main(effectiveArgs);
    }
}
