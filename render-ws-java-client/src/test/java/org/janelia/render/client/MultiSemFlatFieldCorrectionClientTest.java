package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MultiSemFlatFieldCorrectionClient} class.
 *
 * @author Michael Innerberger
 */
public class MultiSemFlatFieldCorrectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MultiSemFlatFieldCorrectionClient.Parameters());
    }

    public static void main(String[] args) {
        args = new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53d",
                "--project", "slab_000_to_009",
                "--stackPattern", "s000_.*",
                "--targetStackSuffix", "_corrected",
                "--inputRoot", "/nrs/hess/data/hess_wafer_53/raw/imaging/msem",
                "--outputRoot", "/nrs/hess/render/flatfield/test/data",
                "--flatFieldLocation", "/nrs/hess/render/flatfield/flatfields_n2000",
                "--flatFieldFormat", "flat_field_z%03d_sfov%03d_n2000.tif",
        };

        MultiSemFlatFieldCorrectionClient.main(args);
    }

}
