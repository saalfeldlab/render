package org.janelia.render.client;

import java.io.File;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link PointMatchClient} class.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("SameParameterValue")
public class PointMatchClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new PointMatchClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.
    //
    // To see old ad-hoc tests that visualized matches in ImageJ, look at code before the commit for this line.

    public static void main(final String[] args) {
        final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";

        final String pTileId = "20-12-26_200022_0-0-1.859.0";
        final String qTileId = "20-12-26_200022_0-0-2.859.0";

        final String baseTileUrl = baseDataUrl + "/owner/Z0720_07m_VNC/project/Sec32/stack/v1_acquire/tile";
        final String commonQueryParameters = "?excludeMask=false&normalizeForMatching=true";
        final String pTileUrl =
                baseTileUrl + "/" + pTileId + "/render-parameters" + commonQueryParameters + "&filter=false";
        final String qTileUrl =
                baseTileUrl + "/" + qTileId + "/render-parameters" + commonQueryParameters + "&filter=true";
        final File matchFile = new File("/Users/trautmane/Desktop/matches.json");

        final String argString =
                "--baseDataUrl " + baseDataUrl + " " +
                "--owner Z0720_07m_VNC --collection not_applicable " +
                "--matchStorageFile " + matchFile + " " +
                "--renderScale 0.4 --SIFTfdSize 4 --SIFTminScale 0.25 --SIFTmaxScale 1.0 --SIFTsteps 5 " +
                "--matchRod 0.92 --matchModelType TRANSLATION --matchIterations 1000 " +
                "--matchMaxEpsilonFullScale 3 --matchMinInlierRatio 0.0 " +
                "--firstCanvasPosition LEFT --clipWidth 500 --clipHeight 500 " +
                "--matchMinNumInliers 12 --matchMaxTrust 4 --matchFilter SINGLE_SET " +
                pTileUrl + " " + qTileUrl;

        System.out.println(argString);
        PointMatchClient.main(argString.split(" "));
    }
}
