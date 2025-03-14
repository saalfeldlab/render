package org.janelia.render.client.multisem;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link MFOVMontageMatchPatchClient} class.
 *
 * @author Eric Trautman
 */
public class MFOVMontageMatchPatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MFOVMontageMatchPatchClient.Parameters());
    }

    public static void main(final String[] args) {

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--stack", "w60_s360_r00_d20_gc",
                "--xyNeighborFactor", "0.6", // for tiles completely within mFov
                "--sameLayerDerivedMatchWeight", "0.15",
//                "--crossLayerDerivedMatchWeight", "0.1",
                "--startPositionMatchWeight", "0.000001",
//                "--pTileId", "w60_magc0160_scan081_m0030_r81_s55",
//                "--qTileId", "w60_magc0160_scan081_m0030_r88_s84",
                "--mfov", "0160_m0045",
                "--matchStorageFile", "/Users/trautmane/Desktop/matchStorageFile.json",
//                "--matchCollection", "w60_s360_r00_d20_gc_match",
//                "--matchStorageCollection", "w60_s360_r00_d20_gc_match_test",
                "--onlyPatchCompletelyUnconnectedTiles"
        };

        MFOVMontageMatchPatchClient.main(effectiveArgs);

    }
}
