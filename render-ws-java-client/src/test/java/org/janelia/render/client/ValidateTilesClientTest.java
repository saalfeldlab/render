package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ValidateTilesClient} class.
 *
 * @author Eric Trautman
 */
public class ValidateTilesClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ValidateTilesClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_check_528_py_solve",
                "--validatorClass", "org.janelia.alignment.spec.validator.TemTileSpecValidator",
                "--validatorData", "minCoordinate:-500000,maxCoordinate:500000,minSize:500,maxSize:5000",
                "528.0",
        };
        
        ValidateTilesClient.main(effectiveArgs);
    }

}
