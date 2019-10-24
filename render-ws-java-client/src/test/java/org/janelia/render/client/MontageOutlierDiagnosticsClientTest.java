package org.janelia.render.client;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MontageOutlierDiagnosticsClient} class.
 *
 * @author Eric Trautman
 */
public class MontageOutlierDiagnosticsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MontageOutlierDiagnosticsClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "flyTEM",
                "--project", "FAFB_montage",
                "--stack", "v15_montage_check_916",
                "--z", "916",
                "--matchCollection", "FAFB_montage_fix",
                "--outlierCsv", "/Users/trautmane/Desktop/v15/fix_0916/manual_outlier_pair_data_b.csv",
                "--rootOutputDirectory", "/Users/trautmane/Desktop/v15/fix_0916/hack"
        };
        MontageOutlierDiagnosticsClient.main(effectiveArgs);
    }

    //            LOG.debug("before: {}", renderParameters.getTileSpecs().stream().map(TileSpec::getTileId).collect(Collectors.toList()));
    //            renderParameters.sortTileSpecs((o1, o2) -> o2.getTileId().compareTo(o1.getTileId()));
    //            LOG.debug("after: {}", renderParameters.getTileSpecs().stream().map(TileSpec::getTileId).collect(Collectors.toList()));
}
