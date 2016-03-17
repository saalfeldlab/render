package org.janelia.render.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.match.CanvasMatches;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link PointMatchClient} class.
 *
 * @author Eric Trautman
 */
public class PointMatchClientTest {

    @Test
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
        final String expectedMatchGroupId = "99.0";
        final String expectedMatchId = "160102030405111111.99.0";
        Assert.assertEquals("invalid derived matchGroupId", expectedMatchGroupId, firstCanvasData.getMatchGroupId());
        Assert.assertEquals("invalid derived matchId", expectedMatchId, firstCanvasData.getMatchId());

        client.extractFeatures();

        int featureCount;
        for (final PointMatchClient.CanvasData canvasData : canvasDataMap.values()) {
            featureCount = canvasData.getNumberOfFeatures();
            Assert.assertTrue("only " + featureCount + " features found for " + canvasData, featureCount > 100);
        }

        final List<CanvasMatches> canvasMatchesList = client.deriveMatches();
        Assert.assertEquals("invalid number of matches derived", 2, canvasMatchesList.size());

        final CanvasMatches canvasMatches = canvasMatchesList.get(0);
        Assert.assertEquals("invalid pGroupId", expectedMatchGroupId, canvasMatches.getpGroupId());
        Assert.assertEquals("invalid pId", expectedMatchId, canvasMatches.getpId());
    }

    private String getRenderParameterPath(final String tileName) {
        return "src/test/resources/point-match-test/" + tileName + "_render_parameters.json";
    }
}
