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
    
}