package org.janelia.render.client;

import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests the {@link RenderDataClient} class.
 *
 * All tests are "ignored" because of the dependency on a real web server.
 * They can be configured to run as needed in specific environments.
 *
 * @author Eric Trautman
 */
@Ignore
public class RenderDataClientTest {

    private RenderDataClient renderDataClient;
    private String stack;
    private Double z;

    @Before
    public void setup() {
        renderDataClient = new RenderDataClient("http://renderer-dev:8080/render-ws/v1", "flyTEM", "FAFB00");
        stack = "v14_align_tps_20170818";
        z = 3451.0;
    }

    @Test
    public void testGetLikelyUniqueId()
            throws Exception {

        final String likelyUniqueId = renderDataClient.getLikelyUniqueId();
        Assert.assertNotNull("null id", likelyUniqueId);
    }

    @Test
    public void testGetStackMetaData()
            throws Exception {

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
        Assert.assertNotNull("null meta data", stackMetaData);

        final StackId stackId = stackMetaData.getStackId();
        Assert.assertNotNull("null stackId ", stackId);

        Assert.assertEquals("invalid stack", stack, stackId.getStack());
    }

    @Test
    public void testGetStackZValues()
            throws Exception {

        final List<Double> zValues = renderDataClient.getStackZValues(stack);
        Assert.assertNotNull("null zValues", zValues);

        Assert.assertTrue("not enough zValues", zValues.size() > 10);
    }

    @Test
    public void testGetTileBounds()
            throws Exception {

        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, z);
        Assert.assertNotNull("null tileBoundsList", tileBoundsList);

        Assert.assertTrue("not enough tileBounds", tileBoundsList.size() > 100);
    }

    @Test
    public void testGetResolvedTiles()
            throws Exception {

        final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);
        Assert.assertNotNull("null resolvedTiles", resolvedTiles);

        Assert.assertTrue("not enough tiles", resolvedTiles.getTileCount() > 100);
    }

}
