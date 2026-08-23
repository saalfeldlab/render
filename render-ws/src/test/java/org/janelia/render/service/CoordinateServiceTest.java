package org.janelia.render.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link org.janelia.render.service.CoordinateService} class.
 *
 * @author Eric Trautman
 */
public class CoordinateServiceTest {

    private static StackId stackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static CoordinateService service;

    @BeforeAll
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        final RenderDao dao = new RenderDao(embeddedMongoDb.getMongoClient());
        service = new CoordinateService(dao);

        embeddedMongoDb.importCollection(stackId.getTileCollectionName(),
                                         new File("src/test/resources/mongodb/elastic-3903.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(stackId.getTransformCollectionName(),
                                         new File("src/test/resources/mongodb/elastic-transform.json"),
                                         true,
                                         false,
                                         true);
    }

    @AfterAll
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testSinglePointCoordinateMethods() throws Exception {

        final Double x = 9000.0;
        final Double y = 7000.0;

        final List<TileCoordinates> localCoordinatesList =
                service.getLocalCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            x,
                                            y,
                                            Z);

        assertEquals(1, localCoordinatesList.size(),
                     "invalid number of tiles found for (" + x + "," + y + ")");

        final TileCoordinates localCoordinates = localCoordinatesList.get(0);

        validateCoordinates("local",
                            localCoordinates,
                            ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                            true,
                            null,
                            null,
                            Z);

        final double[] local = localCoordinates.getLocal();
        final TileCoordinates worldCoordinates =
                service.getWorldCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                                            local[0],
                                            local[1]);
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
        final double[][] points = new double[][]{
                {9000.0, 7000.0},
                {9010.0, 7010.0},
                {109010.0, 107010.0},
                {9020.0, 7020.0}
        };

        final List<TileCoordinates> worldCoordinateList = new ArrayList<>();
        for (final double[] point : points) {
            worldCoordinateList.add(TileCoordinates.buildWorldInstance(null, point));
        }

        final List<List<TileCoordinates>> localCoordinatesListOfLists =
                service.getLocalCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            Z,
                                            worldCoordinateList);

        assertNotNull(localCoordinatesListOfLists, "null local list retrieved");
        assertEquals(worldCoordinateList.size(), localCoordinatesListOfLists.size(),
                     "invalid local list size");

        final List<TileCoordinates> localCoordinatesList = new ArrayList<>();
        for (final List<TileCoordinates> nestedList : localCoordinatesListOfLists) {
            localCoordinatesList.addAll(nestedList);
        }

        TileCoordinates tileCoordinates;
        for (int i = 0; i < localCoordinatesList.size(); i++) {
            tileCoordinates = localCoordinatesList.get(i);
            if (i == errorPointIndex) {
                assertTrue(tileCoordinates.hasError(), "local list [" + i + "] should have error");
                assertNotNull(tileCoordinates.getWorld(),
                              "local list [" + i + "] with error should have world values");
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



        assertNotNull(worldCoordinatesList, "null world list retrieved");
        assertEquals(localCoordinatesList.size(), worldCoordinatesList.size(),
                     "invalid world list size");

        for (int i = 0; i < worldCoordinatesList.size(); i++) {
            tileCoordinates = worldCoordinatesList.get(i);
            if (i == errorPointIndex) {
                assertTrue(tileCoordinates.hasError(), "world list [" + i + "] should have error");
                assertNotNull(tileCoordinates.getWorld(),
                              "world list [" + i + "] with error should have world values");
            } else {
                validateCoordinates("world list [" + i + "]",
                                    tileCoordinates,
                                    ID_FOR_TILE_WITH_REAL_TRANSFORMS,
                                    false,
                                    points[i][0],
                                    points[i][1],
                                    Z);
            }
        }
    }

    @Test
    public void testPointWithMultipleTiles() throws Exception {

        // coordinate (8000, 5900) was chosen because it is invertible in both tiles 252 and 253
        final List<TileCoordinates> localCoordinateList =
                service.getLocalCoordinates(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack(),
                                            8000.0, //
                                            5900.0, //
                                            Z);

        assertNotNull(localCoordinateList, "null local list retrieved");
        assertEquals(2, localCoordinateList.size(),
                     "invalid local list size");

        TileCoordinates localCoordinates = localCoordinateList.get(0);
        assertNotNull(localCoordinates, "null first coordinates");
        assertFalse(localCoordinates.isVisible(),
                    "first coordinates should NOT be marked as visible");

        localCoordinates = localCoordinateList.get(1);
        assertNotNull(localCoordinates, "null second coordinates");
        assertTrue(localCoordinates.isVisible(),
                   "second coordinates should be marked as visible");
    }

    private void validateCoordinates(final String context,
                                     final TileCoordinates coordinates,
                                     final String expectedTileId,
                                     final boolean isLocal,
                                     final Double expectedX,
                                     final Double expectedY,
                                     final Double expectedZ) {

        assertNotNull(coordinates, context + " coordinates are null");
        assertEquals(expectedTileId, coordinates.getTileId(), context + " tileId is invalid");

        double[] values;
        String valueContext;
        if (isLocal) {
            valueContext = context + " local values";
            assertNull(coordinates.getWorld(), context + " world values should be null");
            values = coordinates.getLocal();
        } else {
            valueContext = context + " world values";
            assertNull(coordinates.getLocal(), context + " local values should be null");
            values = coordinates.getWorld();
        }

        assertNotNull(values, valueContext + " are null");
        assertEquals(3, values.length, "invalid number of " + valueContext);

        if (expectedX != null) {
            assertEquals(expectedX, values[0], ACCEPTABLE_DELTA, "incorrect x value for " + valueContext);
        }

        if (expectedY != null) {
            assertEquals(expectedY, values[1], ACCEPTABLE_DELTA, "incorrect y value for " + valueContext);
        }

        assertEquals(expectedZ, values[2], ACCEPTABLE_DELTA, "incorrect z value for " + valueContext);
    }

    private static final String ID_FOR_TILE_WITH_REAL_TRANSFORMS = "254-with-real-transforms";
    private static final Double Z = 3903.0;
    private static final double ACCEPTABLE_DELTA = 0.1;
}
