package org.janelia.render.service.dao;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests {@link RenderDao} methods or error cases that won't change stored data.
 * This allows the embedded database to be setup once for all of the tests and to be safely shared.
 * Avoiding setting up the database for each test reduces the amount of time each test takes.
 *
 * @author Eric Trautman
 */
public class RenderDaoReadOnlyTest {

    private static StackId stackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDao dao;

    @BeforeClass
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());

        embeddedMongoDb.importCollection(RenderDao.STACK_META_DATA_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/admin__stack_meta_data.json"),
                                         true,
                                         false,
                                         true);

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

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetOwners() throws Exception {
        final List<String> list = dao.getOwners();

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of owners found", 1, list.size());
    }

    @Test
    public void testGetStackMetaDataListForOwner() throws Exception {
        final List<StackMetaData> list = dao.getStackMetaDataListForOwner(stackId.getOwner());

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of stacks found", 3, list.size());
    }

    @Test
    public void testGetStackMetaData() throws Exception {

        final Integer expectedLayoutWidth = 2600;
        final Integer expectedLayoutHeight = 2200;

        final StackMetaData stackMetaData = dao.getStackMetaData(stackId);

        Assert.assertNotNull("null stack meta data retrieved", stackMetaData);
        Assert.assertEquals("invalid layout width", expectedLayoutWidth, stackMetaData.getLayoutWidth());
        Assert.assertEquals("invalid layout height", expectedLayoutHeight, stackMetaData.getLayoutHeight());
    }

    @Test
    public void testGetParameters() throws Exception {

        final Double x = 1000.0;
        final Double y = 3000.0;
        final Double z = 3903.0;
        final Integer width = 5000;
        final Integer height = 2000;
        final Double scale = 0.5;

        RenderParameters parameters = dao.getParameters(stackId, null, x, y, z, width, height, scale);

        Assert.assertNotNull("null parameters retrieved", parameters);
        Assert.assertEquals("invalid width parsed", width.intValue(), parameters.getWidth());

        // validate that dao parameters can be re-serialized
        try {
            final String json = parameters.toJson();
            Assert.assertNotNull("null json string produced for parameters", json);
        } catch (final Exception e) {
            LOG.error("failed to serialize json for " + parameters, e);
            Assert.fail("retrieved parameters cannot be re-serialized to json");
        }

        parameters.initializeDerivedValues();
        List<TileSpec> tileSpecs = parameters.getTileSpecs();
        Assert.assertNotNull("null tile specs value after init", tileSpecs);
        Assert.assertEquals("invalid number of tiles after init", 6, tileSpecs.size());

        ListTransformSpec transforms;
        for (final TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            Assert.assertTrue("tileSpec " + tileSpec.getTileId() + " is not fully resolved",
                              transforms.isFullyResolved());
        }

        parameters = dao.getParameters(stackId, groupId, x, y, z, width, height, scale);

        Assert.assertNotNull("null parameters retrieved for group", parameters);
        tileSpecs = parameters.getTileSpecs();
        Assert.assertNotNull("null tile specs returned for group", tileSpecs);
        Assert.assertEquals("invalid number of tiles for group", 2, tileSpecs.size());

        for (final TileSpec tileSpec : tileSpecs) {
            Assert.assertEquals("tileSpec " + tileSpec.getTileId() + " has invalid groupId",
                                groupId, tileSpec.getGroupId());
        }

    }

    @Test
    public void testGetTileSpec() throws Exception {
        final String existingTileId = "134";
        final TileSpec tileSpec = dao.getTileSpec(stackId, existingTileId, false);
        Assert.assertNotNull("null tileSpec retrieved", tileSpec);
        Assert.assertEquals("invalid tileId retrieved", existingTileId, tileSpec.getTileId());
    }

    @Test
    public void testGetTileSpecs() throws Exception {
        final List<TileSpec> list = dao.getTileSpecs(stackId, 3903.0);
        Assert.assertNotNull("null tile spec list retrieved", list);
        Assert.assertEquals("invalid number of tile specs retrieved", 12, list.size());
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTileSpecWithBadId() throws Exception {
        dao.getTileSpec(stackId, "missingId", false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveTileSpecWithBadTransformReference() throws Exception {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setZ(12.3);
        tileSpec.setTileId("bad-ref-tile");
        final List<TransformSpec> list = new ArrayList<>();
        list.add(new ReferenceTransformSpec("missing-id"));
        tileSpec.addTransformSpecs(list);

        dao.saveTileSpec(stackId, tileSpec);
    }

    @Test
    public void testGetTransformSpec() throws Exception {
        final TransformSpec transformSpec = dao.getTransformSpec(stackId, "2");
        Assert.assertNotNull("null transformSpec retrieved", transformSpec);
        Assert.assertTrue("invalid type retrieved", transformSpec instanceof ListTransformSpec);
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTransformSpecWithBadId() throws Exception {
        dao.getTransformSpec(stackId, "missingId");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveTransformSpecWithBadTransformReference() throws Exception {
        final ListTransformSpec listSpec = new ListTransformSpec("bad-ref-transform", null);
        listSpec.addSpec(new ReferenceTransformSpec("missing-id"));

        dao.saveTransformSpec(stackId, listSpec);
    }

    @Test
    public void testGetZValues() throws Exception {
        validateZValues("",                      dao.getZValues(stackId), 2);
        validateZValues("between 3900 and 4000", dao.getZValues(stackId, 3900.0, 4000.0), 2);
        validateZValues("after 3900",            dao.getZValues(stackId, 3900.0, null),   2);
        validateZValues("before 4000",           dao.getZValues(stackId, null,   4000.0), 2);
        validateZValues("between 3911 and 3912", dao.getZValues(stackId, 3911.0, 3912.0), 0);
    }

    private void validateZValues(final String context,
                                 final List<Double> list,
                                 final int expectedCount) {
        Assert.assertNotNull("null list retrieved for search " + context, list);
        Assert.assertEquals("invalid number of sections found " + context, expectedCount, list.size());
    }

    @Test
    public void testGetLayerBounds() throws Exception {
        final Double expectedMinX = 1094.0;
        final Double expectedMinY = 1769.0;
        final Double expectedMaxX = 9917.0;
        final Double expectedMaxY = 8301.0;

        final Double z = 3903.0;

        final Bounds bounds = dao.getLayerBounds(stackId, z);

        Assert.assertNotNull("null layer bounds retrieved", bounds);
        Assert.assertEquals("invalid layer minX", expectedMinX, bounds.getMinX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer minY", expectedMinY, bounds.getMinY(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer minZ", z, bounds.getMinZ(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxX", expectedMaxX, bounds.getMaxX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxY", expectedMaxY, bounds.getMaxY(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxZ", z, bounds.getMaxZ(), BOUNDS_DELTA);
    }

    @Test
    public void testGetTileBoundsForZ() throws Exception {
        final Double z = 3903.0;
        final List<TileBounds> list = dao.getTileBoundsForZ(stackId, z);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of tiles found", 12, list.size());

        TileBounds tileBounds = null;
        for (final TileBounds tb : list) {
            if ("134".equals(tb.getTileId())) {
                tileBounds = tb;
            }
        }

        Assert.assertNotNull("tile 134 missing from tileBounds list", tileBounds);
        Assert.assertTrue("bound box not defined tile 134", tileBounds.isBoundingBoxDefined());
    }

    @Test
    public void testGetTileBoundsForSection() throws Exception {
        final String sectionId = "3903.0";
        final List<TileBounds> list = dao.getTileBoundsForSection(stackId, sectionId);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of tiles found", 2, list.size());
    }

    @Test
    public void testWriteCoordinatesWithTileIds() throws Exception {
        final Double z = 3903.0;
        final List<TileCoordinates> worldCoordinates = new ArrayList<>();
        worldCoordinates.add(TileCoordinates.buildWorldInstance(null, new double[]{1900, 3000}));
        worldCoordinates.add(TileCoordinates.buildWorldInstance(null, new double[]{3700, 3000}));
        worldCoordinates.add(TileCoordinates.buildWorldInstance(null, new double[]{4500, 3000}));

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeCoordinatesWithTileIds(stackId, z, worldCoordinates, outputStream);

        final String json = outputStream.toString();
        final List<List<TileCoordinates>> worldCoordinatesWithTileIds = TileCoordinates.fromJsonArrayOfArrays(json);

        Assert.assertEquals("invalid number of lists returned",
                            worldCoordinates.size(), worldCoordinatesWithTileIds.size());

        // first coordinate
        List<TileCoordinates> tileCoordinatesList = worldCoordinatesWithTileIds.get(0);

        Assert.assertEquals("invalid number of tiles found for first coordinate",
                            1, tileCoordinatesList.size());

        TileCoordinates tileCoordinates = tileCoordinatesList.get(0);

        Assert.assertEquals("invalid tileId for first coordinate",
                            "134", tileCoordinates.getTileId());

        // second coordinate
        tileCoordinatesList = worldCoordinatesWithTileIds.get(1);

        Assert.assertEquals("invalid number of tiles found for second coordinate",
                            2, tileCoordinatesList.size());

        tileCoordinates = tileCoordinatesList.get(0);

        Assert.assertEquals("invalid tileId for second coordinate, first tile",
                            "134", tileCoordinates.getTileId());

        tileCoordinates = tileCoordinatesList.get(1);

        Assert.assertEquals("invalid tileId for second coordinate, second tile",
                            "171", tileCoordinates.getTileId());
    }

    @Test
    public void testGetResolvedTiles() throws Exception {
        final Double z = 3903.0;

        ResolvedTileSpecCollection resolvedTiles = dao.getResolvedTiles(stackId, z);
        Assert.assertNotNull("null collection retrieved for z query", resolvedTiles);
        Assert.assertEquals("invalid number of tiles found for z query", 12, resolvedTiles.getTileCount());

        resolvedTiles = dao.getResolvedTiles(stackId, null, null, groupId, null, null, null, null);
        Assert.assertNotNull("null collection retrieved for groupId query", resolvedTiles);
        Assert.assertEquals("invalid number of tiles found for groupId query", 3, resolvedTiles.getTileCount());


        resolvedTiles = dao.getResolvedTiles(stackId, null, null, groupId, 3950.0, null, null, null);
        Assert.assertNotNull("null collection retrieved for groupId with minX query", resolvedTiles);
        Assert.assertEquals("invalid number of tiles found for groupId with minX query", 1, resolvedTiles.getTileCount());

        resolvedTiles = dao.getResolvedTiles(stackId, 3903.0, null, null, null, null, null, null);
        Assert.assertNotNull("null collection retrieved for min z query", resolvedTiles);
        Assert.assertEquals("invalid number of tiles found for min z query", 14, resolvedTiles.getTileCount());

        resolvedTiles = dao.getResolvedTiles(stackId, null, 3903.0, null, null, null, null, null);
        Assert.assertNotNull("null collection retrieved for max z query", resolvedTiles);
        Assert.assertEquals("invalid number of tiles found for max z query", 12, resolvedTiles.getTileCount());

        resolvedTiles = dao.getResolvedTiles(stackId, 3903.1, 3905.0, null, null, null, null, null);
        Assert.assertNotNull("null collection retrieved for min/max z query", resolvedTiles);
        Assert.assertEquals("invalid number of tiles found for min/max z query", 2, resolvedTiles.getTileCount());

    }

    @Test
    public void testWriteTileIds() throws Exception {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeTileIds(stackId, outputStream);

        final String[] tileIds = outputStream.toString().split(",");

        Assert.assertEquals("invalid number of tileIds written for query", 14, tileIds.length);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDaoReadOnlyTest.class);
    private static final Double BOUNDS_DELTA = 0.1;
    private static final String groupId = "A";
}
