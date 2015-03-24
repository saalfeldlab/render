package org.janelia.render.client;

import java.util.Arrays;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CoordinateClient} class.
 *
 * @author Eric Trautman
 */
public class CoordinateClientTest {

    @Test
    public void testRoundTripMapping() throws Exception {
        final String stackName = "test-stack";
        final Double z = 9.9;
        final CoordinateClient client = new CoordinateClient(stackName, z, null);

        final String transformId = "transform-1";
        final AffineModel2D noOpAffine = new AffineModel2D();
        final TransformSpec transform1 = new LeafTransformSpec(transformId,
                                                               null,
                                                               noOpAffine.getClass().getName(),
                                                               noOpAffine.toDataString());

        final String tile1json = "{ \"tileId\": \"tile-1\", \"z\": " + z + ", \"width\": 2560, \"height\": 2160}";
        final TileSpec tile1 = TileSpec.fromJson(tile1json);

        final TileCoordinates worldCoord = TileCoordinates.buildWorldInstance("tile-1", new double[] {1.0, 2.0});
        List<List<TileCoordinates>> worldListOfLists = Arrays.asList(Arrays.asList(worldCoord));
        ResolvedTileSpecCollection tiles = new ResolvedTileSpecCollection(stackName,
                                                                          z,
                                                                          Arrays.asList(transform1),
                                                                          Arrays.asList(tile1));

        // Hack: Add ref transform to tile after adding to collection so that it is not resolved by constructor.
        //       This should mimic what happens after JSON deserialization.
        final TransformSpec transform1Ref = new ReferenceTransformSpec(transformId);
        tile1.addTransformSpecs(Arrays.asList(transform1Ref));

        // Then force resolution ...
        tiles.resolveTileSpecs();

        List<List<TileCoordinates>> localListOfLists = client.worldToLocal(worldListOfLists, tiles);

        Assert.assertEquals("invalid number of local lists returned", 1, localListOfLists.size());

        List<TileCoordinates> localList = localListOfLists.get(0);

        Assert.assertEquals("invalid number of coordinates in first local list", 1, localList.size());

        final TileCoordinates localCoord = localList.get(0);

        Assert.assertFalse("returned local coordinates have error: " + localCoord.toJson(), localCoord.hasError());

        final double acceptableDelta = 0.001;
        Assert.assertEquals("invalid local x coordinate returned",
                            worldCoord.getWorld()[0], localCoord.getLocal()[0], acceptableDelta);

        Assert.assertEquals("invalid local y coordinate returned",
                            worldCoord.getWorld()[1], localCoord.getLocal()[1], acceptableDelta);

        final List<TileCoordinates> roundTripWorldList = client.localToWorld(localListOfLists, tiles);

        Assert.assertEquals("incorrect number of round trip world coordinates", 1, roundTripWorldList.size());

        final TileCoordinates roundTripWorldCoord = roundTripWorldList.get(0);
        Assert.assertEquals("incorrect round trip tile id", worldCoord.getTileId(), roundTripWorldCoord.getTileId());

        final double[] expectedArray = worldCoord.getWorld();
        final double[] actualArray = roundTripWorldCoord.getWorld();
        Assert.assertEquals("incorrect round trip world array length", expectedArray.length + 1, actualArray.length);
        for (int i = 0; i < expectedArray.length; i++) {
            Assert.assertEquals("incorrect round trip value for item " + i, expectedArray[i], actualArray[i], 0.01);
        }
    }

}
