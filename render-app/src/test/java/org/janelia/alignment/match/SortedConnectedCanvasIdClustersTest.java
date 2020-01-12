package org.janelia.alignment.match;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link SortedConnectedCanvasIdClusters} class.
 *
 * @author Eric Trautman
 */
public class SortedConnectedCanvasIdClustersTest {

    @Test
    public void testCrossLayerClusters() {
        final String jsonArray =
                "[\n" +
                "  {\n" +
                "    \"pGroupId\": \"36924.0\",\n" +
                "    \"pId\": \"z_36924.0_box...\",\n" +
                "    \"qGroupId\": \"36925.0\",\n" +
                "    \"qId\": \"z_36925.0_box...\",\n" +
                "    \"matchCount\": 1\n" +
                "  },\n" +
                "  {\n" +
                "    \"pGroupId\": \"36920.0\",\n" +
                "    \"pId\": \"z_36920.0_box...\",\n" +
                "    \"qGroupId\": \"36921.0\",\n" +
                "    \"qId\": \"z_36921.0_box...\",\n" +
                "    \"matchCount\": 2\n" +
                "  },\n" +
                "  {\n" +
                "    \"pGroupId\": \"36921.0\",\n" +
                "    \"pId\": \"z_36921.0_box...\",\n" +
                "    \"qGroupId\": \"36922.0\",\n" +
                "    \"qId\": \"z_36922.0_box...\",\n" +
                "    \"matchCount\": 3\n" +
                "  }\n" +
                "]";

        final List<CanvasMatches> canvasMatchesList = CanvasMatches.fromJsonArray(jsonArray);
        final SortedConnectedCanvasIdClusters canvasIdClusters =
                new SortedConnectedCanvasIdClusters(canvasMatchesList);

        Assert.assertEquals("incorrect number of connected clusters found",
                            2, canvasIdClusters.size());
    }

    @Test
    public void testMergeOverlappingClusters() {

        // a1   b1
        // |    |
        // a2   b2
        // |    |
        // a3   b3
        // |    |
        // a4 - b4

        final String jsonArray1 =
                "[\n" +
                "  { \"pGroupId\":\"1\", \"pId\":\"a1\", \"qGroupId\":\"2\", \"qId\":\"a2\", \"matchCount\": 1},\n" +
                "  { \"pGroupId\":\"2\", \"pId\":\"a2\", \"qGroupId\":\"3\", \"qId\":\"a3\", \"matchCount\": 1},\n" +

                "  { \"pGroupId\":\"1\", \"pId\":\"b1\", \"qGroupId\":\"2\", \"qId\":\"b2\", \"matchCount\": 1},\n" +
                "  { \"pGroupId\":\"2\", \"pId\":\"b2\", \"qGroupId\":\"3\", \"qId\":\"b3\", \"matchCount\": 1}" +
                "]";

        final String jsonArray2 =
                "[\n" +
                "  { \"pGroupId\":\"3\", \"pId\":\"a3\", \"qGroupId\":\"4\", \"qId\":\"a4\", \"matchCount\": 1},\n" +
                "  { \"pGroupId\":\"3\", \"pId\":\"b3\", \"qGroupId\":\"4\", \"qId\":\"b4\", \"matchCount\": 1},\n" +

                "  { \"pGroupId\":\"4\", \"pId\":\"a4\", \"qGroupId\":\"4\", \"qId\":\"b4\", \"matchCount\": 1}" +
                "]";

        final List<CanvasMatches> matchesBatch1 = CanvasMatches.fromJsonArray(jsonArray1);
        final SortedConnectedCanvasIdClusters clustersBatch1 = new SortedConnectedCanvasIdClusters(matchesBatch1);

        Assert.assertEquals("incorrect number of connected clusters found for batch 1",
                            2, clustersBatch1.size());

        final List<CanvasMatches> matchesBatch2 = CanvasMatches.fromJsonArray(jsonArray2);
        final SortedConnectedCanvasIdClusters clustersBatch2 = new SortedConnectedCanvasIdClusters(matchesBatch2);

        Assert.assertEquals("incorrect number of connected clusters found for batch 2",
                            1, clustersBatch2.size());

        clustersBatch1.mergeOverlappingClusters(clustersBatch2);

        Assert.assertEquals("incorrect number of connected clusters found for merged batches",
                            1, clustersBatch1.size());
    }
}