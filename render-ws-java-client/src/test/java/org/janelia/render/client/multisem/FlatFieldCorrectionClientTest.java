package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link FlatFieldCorrectionClient} class.
 *
 * @author Michael Innerberger
 */
public class FlatFieldCorrectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new FlatFieldCorrectionClient.Parameters());
    }

    public static void main(String[] args) {
        args = new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53d",
                "--project", "slab_070_to_079",
                "--stackPattern", "s070_m104",
                "--targetStackSuffix", "_corrected",
                "--inputRoot", "/nrs/hess/data/hess_wafer_53/raw/imaging/msem",
                "--outputRoot", "/nrs/hess/render/flatfield/test/corrected",
                "--flatFieldLocation", "/nrs/hess/render/flatfield/flatfields_n2000",
                "--flatFieldFormat", "flat_field_z%03d_sfov%03d_n2000.tif",
        };

        FlatFieldCorrectionClient.main(args);
    }

}
