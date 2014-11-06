package org.janelia.render.service;

import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.render.service.dao.RenderParametersDao;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the {@link org.janelia.render.service.CoordinateService} class.
 *
 * @author Eric Trautman
 */
public class CoordinateServiceTest {

    private static StackId stackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static CoordinateService service;

    @BeforeClass
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(stackId.getDatabaseName());
        final RenderParametersDao dao = new RenderParametersDao(embeddedMongoDb.getMongoClient());
        service = new CoordinateService(dao);

        embeddedMongoDb.importCollection(RenderParametersDao.TILE_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/elastic-3903.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(RenderParametersDao.TRANSFORM_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/elastic-transform.json"),
                                         true,
                                         false,
                                         true);
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testSinglePointCoordinateMethods() throws Exception {

        final Double x = 9000.0;
        final Double y = 7000.0;

        final TileCoordinates localCoordinates =
                service.getLocalCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            x,
                                            y,
                                            Z);
        validateCoordinates("local",
                            localCoordinates,
                            ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                            true,
                            null,
                            null,
                            Z);

        final float[] local = localCoordinates.getLocal();
        final TileCoordinates worldCoordinates =
                service.getWorldCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                                            (double) local[0],
                                            (double) local[1]);
        validateCoordinates("world",
                            worldCoordinates,
                            ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                            false,
                            x,
                            y,
                            Z);
    }

    @Test
    public void testMultiplePointCoordinateMethods() throws Exception {

        final int errorPointIndex = 2;
        final float[][] points = new float[][]{
                {9000.0f, 7000.0f},
                {9010.0f, 7010.0f},
                {109010.0f, 107010.0f},
                {9020.0f, 7020.0f}
        };

        final List<TileCoordinates> worldCoordinateList = new ArrayList<TileCoordinates>();
        for (float[] point : points) {
            worldCoordinateList.add(TileCoordinates.buildWorldInstance(null, point));
        }

        final List<TileCoordinates> localCoordinatesList =
                service.getLocalCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            Z,
                                            worldCoordinateList);

        Assert.assertNotNull("null local list retrieved", localCoordinatesList);
        Assert.assertEquals("invalid local list size",
                            worldCoordinateList.size(), localCoordinatesList.size());

        TileCoordinates tileCoordinates;
        for (int i = 0; i < localCoordinatesList.size(); i++) {
            tileCoordinates = localCoordinatesList.get(i);
            if (i == errorPointIndex) {
                Assert.assertTrue("local list [" + i + "] should have error", tileCoordinates.hasError());
                Assert.assertNotNull("local list [" + i + "] with error should have world values",
                                     tileCoordinates.getWorld());
            } else {
                validateCoordinates("local list [" + i + "]",
                                    tileCoordinates,
                                    ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                                    true,
                                    null,
                                    null,
                                    Z);
            }
        }

        final List<TileCoordinates> worldCoordinatesList =
                service.getWorldCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            Z,
                                            localCoordinatesList);



        Assert.assertNotNull("null world list retrieved", worldCoordinatesList);
        Assert.assertEquals("invalid world list size",
                            localCoordinatesList.size(), worldCoordinatesList.size());

        for (int i = 0; i < worldCoordinatesList.size(); i++) {
            tileCoordinates = worldCoordinatesList.get(i);
            if (i == errorPointIndex) {
                Assert.assertTrue("world list [" + i + "] should have error", tileCoordinates.hasError());
                Assert.assertNotNull("world list [" + i + "] with error should have world values",
                                     tileCoordinates.getWorld());
            } else {
                validateCoordinates("world list [" + i + "]",
                                    tileCoordinates,
                                    ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                                    false,
                                    (double) points[i][0],
                                    (double) points[i][1],
                                    Z);
            }
        }
    }

    private void validateCoordinates(String context,
                                     TileCoordinates coordinates,
                                     String expectedTileId,
                                     boolean isLocal,
                                     Double expectedX,
                                     Double expectedY,
                                     Double expectedZ) {

        Assert.assertNotNull(context + " coordinates are null", coordinates);
        Assert.assertEquals(context + " tileId is invalid", expectedTileId, coordinates.getTileId());

        float[] values;
        String valueContext;
        if (isLocal) {
            valueContext = context + " local values";
            Assert.assertNull(context + " world values should be null", coordinates.getWorld());
            values = coordinates.getLocal();
        } else {
            valueContext = context + " world values";
            Assert.assertNull(context + " local values should be null", coordinates.getLocal());
            values = coordinates.getWorld();
        }

        Assert.assertNotNull(valueContext + " are null", values);
        Assert.assertEquals("invalid number of " + valueContext, 3, values.length);

        if (expectedX != null) {
            Assert.assertEquals("incorrect x value for " + valueContext, expectedX, values[0], ACCEPTABLE_DELTA);
        }

        if (expectedY != null) {
            Assert.assertEquals("incorrect y value for " + valueContext, expectedY, values[1], ACCEPTABLE_DELTA);
        }

        Assert.assertEquals("incorrect z value for " + valueContext, expectedZ, values[2], ACCEPTABLE_DELTA);
    }

    private static final String ID_FOR_TILE_WITH_REAL_TRANSFORMS = "254-with-real-transforms";
    private static final Double Z = 3903.0;
    private static final double ACCEPTABLE_DELTA = 0.1;
}
