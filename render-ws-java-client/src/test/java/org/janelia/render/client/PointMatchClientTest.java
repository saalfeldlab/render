package org.janelia.render.client;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PointRoi;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mpicbg.util.ColorStream;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Ignore;
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

    @Ignore
    public void testMultiThreadedClient()
            throws Exception {
        testClient(3);
    }

    private void testClient(final int numberOfThreads)
            throws Exception {

        final String tile1 = getRenderParameterPath("col0075_row0021_cam1");
        final String tile2 = getRenderParameterPath("col0075_row0022_cam3");
        final String tile3 = getRenderParameterPath("col0076_row0021_cam0");

        final String[] args = {
                "--baseDataUrl", "http://renderer-dev:8080/render-ws/v1",
                "--owner", "trautmane",
                "--collection", "pm_client_test_tiles",
                "--numberOfThreads", String.valueOf(numberOfThreads),
                "--renderScale", "0.3",
//                "--debugDirectory", "/Users/trautmane/Desktop",
//                "--matchStorageFile", "/Users/trautmane/Desktop/matches.json",
                tile1, tile2,
                tile1, tile3
        };

        final PointMatchClient.Parameters clientParameters = new PointMatchClient.Parameters();
        clientParameters.parse(args);

        final PointMatchClient client = new PointMatchClient(clientParameters);

        final Map<String, PointMatchClient.CanvasData> canvasDataMap = client.getCanvasUrlToDataMap();

        Assert.assertEquals("invalid number of distinct canvas URLs", 3, canvasDataMap.size());

        final PointMatchClient.CanvasData firstCanvasData = new ArrayList<>(canvasDataMap.values()).get(0);
        final String expectedCanvasGroupId = "99.0";
        final String expectedCanvasId = "160102030405111111.99.0";
        Assert.assertEquals("invalid derived canvasGroupId", expectedCanvasGroupId, firstCanvasData.getCanvasId().getGroupId());
        Assert.assertEquals("invalid derived canvasId", expectedCanvasId, firstCanvasData.getCanvasId().getId());

        client.extractFeatures();

        int featureCount;
        for (final PointMatchClient.CanvasData canvasData : canvasDataMap.values()) {
            featureCount = canvasData.getNumberOfFeatures();
            Assert.assertTrue("only " + featureCount + " features found for " + canvasData, featureCount > 100);
        }

        final List<CanvasMatches> canvasMatchesList = client.deriveMatches();
        Assert.assertEquals("invalid number of matches derived", 2, canvasMatchesList.size());

        final CanvasMatches canvasMatches = canvasMatchesList.get(0);
        Assert.assertEquals("invalid pGroupId", expectedCanvasGroupId, canvasMatches.getpGroupId());
        Assert.assertEquals("invalid pId", expectedCanvasId, canvasMatches.getpId());
    }

    private String getRenderParameterPath(final String tileName) {
        return "src/test/resources/point-match-test/" + tileName + "_render_parameters.json";
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
            if (args.length == 0) {
                showProblemMontageMatches();
            } else if (args.length == 1) {
                showFoldMatches();
            } else {
                saveFullScaleMatches();
            }
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    private static void showProblemMontageMatches()
            throws Exception {

        final String baseDataUrl = "http://renderer-dev:8080/render-ws/v1";

        final String baseTileUrl = "http://renderer:8080/render-ws/v1/owner/flyTEM/project/FAFB00/stack/v12_acquire/tile";
        final String pTile = "/150504171631067035.2409.0";
        final String qTile = "/150504171631067036.2409.0";
        final String queryParameters = "?excludeMask=true&normalizeForMatching=true&width=2760&height=2330&filter=true";

        final String pTileUrl = baseTileUrl + pTile + "/render-parameters" + queryParameters;
        final String qTileUrl = baseTileUrl + qTile + "/render-parameters" + queryParameters;

        final File matchFile = new File("/Users/trautmane/Desktop/matches.json");

        final String argString =
                "--baseDataUrl " + baseDataUrl + " " +
                "--owner flyTEM --collection not_applicable --matchStorageFile " + matchFile + " " +
                "--renderScale 0.33 --SIFTfdSize 4 --SIFTminScale 0.8 --SIFTmaxScale 1.0 --SIFTsteps 3 " +
                "--matchRod 0.92 --matchModelType TRANSLATION --matchIterations 1000 --matchMaxEpsilon 7 --matchMinInlierRatio 0.0 " +
                "--firstCanvasPosition TOP --clipWidth 600 --clipHeight 600 " +
                "--matchMinNumInliers 7 --matchMaxTrust 4 --matchFilter SINGLE_SET " +
                "--pairMaxDeltaStandardDeviation 8.0 " +
                pTileUrl + " " + qTileUrl;

        final String[] args = argString.split(" ");

        final PointMatchClient.Parameters parameters = new PointMatchClient.Parameters();
        parameters.parse(args);

        final PointMatchClient client = new PointMatchClient(parameters);

        client.extractFeatures();

        final List<CanvasMatches> canvasMatchesList = client.deriveMatches();
        if (canvasMatchesList.size() > 0) {
            final Color[] colors = new Color[8]; //{Color.red, Color.cyan, Color.yellow};
            for (int i = 0; i < colors.length; i++) {
                colors[i] = new Color(ColorStream.next());
            }

            showMatches(baseTileUrl + pTile + "/jpeg-image" + queryParameters, canvasMatchesList, true, colors);
            showMatches(baseTileUrl + qTile + "/jpeg-image" + queryParameters, canvasMatchesList, false, colors);
        }
    }

    private static void showFoldMatches()
            throws Exception {

        final String baseDataUrl = "http://renderer-dev:8080/render-ws/v1";

        final String baseTileUrl = "http://renderer:8080/render-ws/v1/owner/flyTEM/project/trautmane_fafb_fold_rough_tiles_j_tier_1/stack/0003x0003_000004/tile";
//        final String pTile = "/z_2213.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0";
//        final String qTile = "/z_2214.0_box_40532_42182_3731_3654_0.274457_set_2213.0_2214.0_0";
        final String pTile = "/z_2213.0_box_40532_42182_3731_3654_0.274457";
        final String qTile = "/z_2214.0_box_40532_42182_3731_3654_0.274457";
//        final String pTile = "/z_2214.0_box_40532_42182_3731_3654_0.274457";
//        final String qTile = "/z_2215.0_box_40532_42182_3731_3654_0.274457";
        final String queryParameters = "?excludeMask=true&normalizeForMatching=true&filter=true";

        final String pTileUrl = baseTileUrl + pTile + "/render-parameters" + queryParameters;
        final String qTileUrl = baseTileUrl + qTile + "/render-parameters" + queryParameters;

        final File matchFile = new File("/Users/trautmane/Desktop/matches.json");

        final String argString =
                "--baseDataUrl " + baseDataUrl + " " +
                "--owner flyTEM --collection not_applicable --matchStorageFile " + matchFile + " " +
                "--renderScale 0.8 --SIFTfdSize 8 --SIFTminScale 0.1 --SIFTmaxScale 1.0 --SIFTsteps 6 " +
                "--matchRod 0.95 --matchModelType AFFINE --matchIterations 1000 --matchMaxEpsilon 5.0 --matchMinInlierRatio 0.0 " +
                "--matchMinNumInliers 40 --matchMaxTrust 30.0 --matchFilter CONSENSUS_SETS " +
                pTileUrl + " " + qTileUrl;

        final String[] args = argString.split(" ");

        final PointMatchClient.Parameters parameters = new PointMatchClient.Parameters();
        parameters.parse(args);

        final PointMatchClient client = new PointMatchClient(parameters);

        client.extractFeatures();

        final List<CanvasMatches> canvasMatchesList = client.deriveMatches();

        final Color[] colors = new Color[8]; //{Color.red, Color.cyan, Color.yellow};
        for (int i = 0; i < colors.length; i++) {
            colors[i] = new Color(ColorStream.next());
        }

        showMatches(baseTileUrl + pTile + "/jpeg-image" + queryParameters, canvasMatchesList, true, colors);
        showMatches(baseTileUrl + qTile + "/jpeg-image" + queryParameters, canvasMatchesList, false, colors);
    }

    private static void showMatches(final String imageUrl,
                                    final List<CanvasMatches> canvasMatchesList,
                                    final boolean isP,
                                    final Color[] colors) {

        final ImagePlus imagePlus = IJ.openImage(imageUrl);
        final Overlay overlay = new Overlay();

        int colorIndex = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            final double[][] points = isP ? canvasMatches.getMatches().getPs() : canvasMatches.getMatches().getQs();
            final float[] xPoints = toFloat(points[0]);
            final float[] yPoints = toFloat(points[1]);
            final PointRoi pointRoi = new PointRoi(xPoints, yPoints, xPoints.length);
            pointRoi.setStrokeColor(colors[colorIndex]);
            pointRoi.setSize(3); // 0: "Tiny", 1: "Small", 2: "Medium", 3: "Large", 4: "Extra Large"}
            overlay.add(pointRoi);
            colorIndex = (colorIndex + 1) % colors.length;
        }

        imagePlus.setOverlay(overlay);
        imagePlus.updateAndDraw();
        imagePlus.show();

    }

    private static float[] toFloat(final double[] doubleArray) {
        final float[] floatArray = new float[doubleArray.length];
        for (int i = 0; i < doubleArray.length; i++) {
            floatArray[i] = (float) doubleArray[i];
        }
        return floatArray;
    }

    private static void saveFullScaleMatches() {

        final String baseDataUrl = "http://renderer-dev:8080/render-ws/v1";
        final StackId stackId = new StackId("flyTEM", "FAFB00", "v12_acquire_merged");

//        final String pTileUrl = getTileUrl(baseDataUrl, stackId, "151215054402010011.1.0");
//        final String qTileUrl = getTileUrl(baseDataUrl, stackId, "151215054132011010.2.0");
//        final String pTileUrl = getTileUrl(baseDataUrl, stackId, "150318163621069162.3222.0");
//        final String qTileUrl = getTileUrl(baseDataUrl, stackId, "150318151111067021.3223.0");
        final String pTileUrl = getTileUrl(baseDataUrl, stackId, "150622171613069111.2210.0");
        final String qTileUrl = getTileUrl(baseDataUrl, stackId, "150622171613071177.2211.0");

        final String[] args = {
                "--baseDataUrl", baseDataUrl,
                "--owner", "flyTEM",
                "--collection", "trautmane_full_scale_test",
                "--matchStorageFile", "/Users/trautmane/Desktop/matches.json",
                "--renderScale", "0.4",
                "--SIFTfdSize", "8", "--SIFTminScale", "0.3", "--SIFTmaxScale", "0.6", "--SIFTsteps", "6",
                "--matchRod", "0.95", "--matchModelType", "AFFINE", "--matchIterations", "1000",
                "--matchMaxEpsilon", "20.0", "--matchMinInlierRatio", "0.0",
                "--matchMinNumInliers", "4", "--matchMaxTrust", "30.0",
                pTileUrl, qTileUrl
        };

        PointMatchClient.main(args);
    }

    private static String getTileUrl(final String baseDataUrl,
                                     final StackId stackId,
                                     final String tileId) {
        return baseDataUrl + "/owner/" + stackId.getOwner() + "/project/" + stackId.getProject() +
               "/stack/" + stackId.getStack() + "/tile/" + tileId +
               "/render-parameters?excludeMask=true&normalizeForMatching=true";
    }

}
