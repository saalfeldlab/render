package org.janelia.render.client;

import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link RenderDataClient} class.
 * All tests are "ignored" because of the dependency on a real web server.
 * They can be configured to run as needed in specific environments.
 *
 * @author Eric Trautman
 */
@Disabled
public class RenderDataClientTest {

    private RenderDataClient renderDataClient;
    private String stack;
    private Double z;

    @BeforeEach
    public void setup() {
        renderDataClient = new RenderDataClient("http://renderer-dev:8080/render-ws/v1",
                                                "hess_wafer_53",
                                                "cut_000_to_009");
        stack = "c000_s095_v01";
        z = 1.0;
    }

    @Test
    public void testGetLikelyUniqueId()
            throws Exception {

        final String likelyUniqueId = renderDataClient.getLikelyUniqueId();
        assertNotNull(likelyUniqueId, "null id");
    }

    @Test
    public void testGetStackMetaData()
            throws Exception {

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
        assertNotNull(stackMetaData, "null meta data");

        final StackId stackId = stackMetaData.getStackId();
        assertNotNull(stackId, "null stackId ");

        assertEquals(stack, stackId.getStack(), "invalid stack");
    }

    @Test
    public void testGetStackZValues()
            throws Exception {

        final List<Double> zValues = renderDataClient.getStackZValues(stack);
        assertNotNull(zValues, "null zValues");

        assertTrue(zValues.size() > 10, "not enough zValues");
    }

    @Test
    public void testGetTileBounds()
            throws Exception {

        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, z);
        assertNotNull(tileBoundsList, "null tileBoundsList");

        assertTrue(tileBoundsList.size() > 100, "not enough tileBounds");
    }

    @Test
    public void testGetResolvedTiles()
            throws Exception {

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);
        assertNotNull(resolvedTiles, "null resolvedTiles");

        assertTrue(resolvedTiles.getTileCount() > 100, "not enough tiles");
    }

    @Test
    public void testGetResolvedTileSpecsWithMatchPairs()
            throws Exception {

        final Bounds bounds = new Bounds(66001.0,   30668.0, 1.0,
                                         78000.0,   43001.0, 36.0);
        final String matchCollectionName = "c000_s095_v01_match_agg2";

        final ResolvedTileSpecsWithMatchPairs tileSpecsWithMatchPairs =
                renderDataClient.getResolvedTilesWithMatchPairs(stack,
                                                                bounds,
                                                                matchCollectionName,
                                                                null,
                                                                null,
                                                                false);

        assertNotNull(tileSpecsWithMatchPairs, "null result");
    }

}
