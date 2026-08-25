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
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    @BeforeAll
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

    @AfterAll
    public static void after() {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetOwners() {
        final List<String> list = dao.getOwners();

        assertNotNull(list, "null list retrieved");
        assertEquals(1, list.size(), "invalid number of owners found");
    }

    @Test
    public void testGetProjects() {
        final List<String> list = dao.getProjects(stackId.getOwner());

        assertNotNull(list, "null list retrieved");
        assertEquals(2, list.size(), "invalid number of projects found");
    }

    @Test
    public void testGetStackMetaDataList() {
        List<StackMetaData> list = dao.getStackMetaDataList(stackId.getOwner(), null);

        assertNotNull(list, "null list retrieved for owner");
        assertEquals(3, list.size(), "invalid number of stacks found for owner");

        list = dao.getStackMetaDataList(stackId.getOwner(), stackId.getProject());

        assertNotNull(list, "null list retrieved for project");
        assertEquals(1, list.size(), "invalid number of stacks found for project");
    }

    @Test
    public void testGetStackMetaData() {

        final Integer expectedLayoutWidth = 2600;
        final Integer expectedLayoutHeight = 2200;

        final StackMetaData stackMetaData = dao.getStackMetaData(stackId);

        assertNotNull(stackMetaData, "null stack meta data retrieved");
        assertEquals(expectedLayoutWidth, stackMetaData.getLayoutWidth(), "invalid layout width");
        assertEquals(expectedLayoutHeight, stackMetaData.getLayoutHeight(), "invalid layout height");
    }

    @Test
    public void testGetParameters() {

        final Double x = 1000.0;
        final Double y = 3000.0;
        final Double z = 3903.0;
        final Integer width = 5000;
        final Integer height = 2000;
        final Double scale = 0.5;

        RenderParameters parameters = dao.getParameters(stackId, null, x, y, z, width, height, scale);

        assertNotNull(parameters, "null parameters retrieved");
        assertEquals(width.intValue(), parameters.getWidth(), "invalid width parsed");

        // validate that dao parameters can be re-serialized
        try {
            final String json = parameters.toJson();
            assertNotNull(json, "null json string produced for parameters");
        } catch (final Exception e) {
            LOG.error("failed to serialize json for " + parameters, e);
            fail("retrieved parameters cannot be re-serialized to json");
        }

        parameters.initializeDerivedValues();
        List<TileSpec> tileSpecs = parameters.getTileSpecs();
        assertNotNull(tileSpecs, "null tile specs value after init");
        assertEquals(6, tileSpecs.size(), "invalid number of tiles after init");

        ListTransformSpec transforms;
        for (final TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            assertTrue(transforms.isFullyResolved(),
                       "tileSpec " + tileSpec.getTileId() + " is not fully resolved");
        }

        parameters = dao.getParameters(stackId, groupId, x, y, z, width, height, scale);

        assertNotNull(parameters, "null parameters retrieved for group");
        tileSpecs = parameters.getTileSpecs();
        assertNotNull(tileSpecs, "null tile specs returned for group");
        assertEquals(2, tileSpecs.size(), "invalid number of tiles for group");

        for (final TileSpec tileSpec : tileSpecs) {
            assertEquals(groupId, tileSpec.getGroupId(),
                         "tileSpec " + tileSpec.getTileId() + " has invalid groupId");
        }

    }

    @Test
    public void testGetTileSpec() {
        final String existingTileId = "134";
        final TileSpec tileSpec = dao.getTileSpec(stackId, existingTileId, false);
        assertNotNull(tileSpec, "null tileSpec retrieved");
        assertEquals(existingTileId, tileSpec.getTileId(), "invalid tileId retrieved");
    }

    @Test
    public void testGetTileSpecs() {
        final List<TileSpec> list = dao.getTileSpecs(stackId, 3903.0);
        assertNotNull(list, "null tile spec list retrieved");
        assertEquals(12, list.size(), "invalid number of tile specs retrieved");
    }

    @Test
    public void testGetTileSpecWithBadId() {
        assertThrows(ObjectNotFoundException.class, () -> dao.getTileSpec(stackId, "missingId", false));
    }

    @Test
    public void testSaveTileSpecWithBadTransformReference() {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setZ(12.3);
        tileSpec.setTileId("bad-ref-tile");
        final List<TransformSpec> list = new ArrayList<>();
        list.add(new ReferenceTransformSpec("missing-id"));
        tileSpec.addTransformSpecs(list);
        assertThrows(IllegalArgumentException.class, () -> dao.saveTileSpec(stackId, tileSpec));
    }

    @Test
    public void testGetTransformSpec() {
        final TransformSpec transformSpec = dao.getTransformSpec(stackId, "2");
        assertNotNull(transformSpec, "null transformSpec retrieved");
        assertTrue(transformSpec instanceof ListTransformSpec, "invalid type retrieved");
    }

    @Test
    public void testGetTransformSpecWithBadId() {
        assertThrows(ObjectNotFoundException.class, () -> dao.getTransformSpec(stackId, "missingId"));
    }

    @Test
    public void testSaveTransformSpecWithBadTransformReference() {
        final ListTransformSpec listSpec = new ListTransformSpec("bad-ref-transform", null);
        listSpec.addSpec(new ReferenceTransformSpec("missing-id"));
        assertThrows(IllegalArgumentException.class, () -> dao.saveTransformSpec(stackId, listSpec));
    }

    @Test
    public void testGetZValues() {
        validateZValues("",                      dao.getZValues(stackId), 2);
        validateZValues("between 3900 and 4000", dao.getZValues(stackId, 3900.0, 4000.0), 2);
        validateZValues("after 3900",            dao.getZValues(stackId, 3900.0, null),   2);
        validateZValues("before 4000",           dao.getZValues(stackId, null,   4000.0), 2);
        validateZValues("between 3911 and 3912", dao.getZValues(stackId, 3911.0, 3912.0), 0);
    }

    private void validateZValues(final String context,
                                 final List<Double> list,
                                 final int expectedCount) {
        assertNotNull(list, "null list retrieved for search " + context);
        assertEquals(expectedCount, list.size(), "invalid number of sections found " + context);
    }

    @Test
    public void testGetLayerBounds() {
        final double expectedMinX = 1094.0;
        final double expectedMinY = 1769.0;
        final double expectedMaxX = 9917.0;
        final double expectedMaxY = 8301.0;

        final double z = 3903.0;

        final Bounds bounds = dao.getLayerBounds(stackId, z);

        assertNotNull(bounds, "null layer bounds retrieved");

        assertAll("invalid layer bounds",
                  () -> assertEquals(expectedMinX, bounds.getMinX(), BOUNDS_DELTA, "invalid layer minX"),
                  () -> assertEquals(expectedMinY, bounds.getMinY(), BOUNDS_DELTA, "invalid layer minY"),
                  () -> assertEquals(z, bounds.getMinZ(), BOUNDS_DELTA, "invalid layer minZ"),
                  () -> assertEquals(expectedMaxX, bounds.getMaxX(), BOUNDS_DELTA, "invalid layer maxX"),
                  () -> assertEquals(expectedMaxY, bounds.getMaxY(), BOUNDS_DELTA, "invalid layer maxY"),
                  () -> assertEquals(z, bounds.getMaxZ(), BOUNDS_DELTA, "invalid layer maxZ"));
    }

    @Test
    public void testGetTileBoundsForZ() {
        final Double z = 3903.0;
        final List<TileBounds> list = dao.getTileBoundsForZ(stackId, z, null);

        assertNotNull(list, "null list retrieved");
        assertEquals(12, list.size(), "invalid number of tiles found");

        TileBounds tileBounds = null;
        for (final TileBounds tb : list) {
            if ("134".equals(tb.getTileId())) {
                tileBounds = tb;
            }
        }

        assertNotNull(tileBounds, "tile 134 missing from tileBounds list");
        assertTrue(tileBounds.isBoundingBoxDefined(), "bound box not defined tile 134");
    }

    @Test
    public void testGetTileBoundsForSection() {
        final String sectionId = "3903.0";
        final List<TileBounds> list = dao.getTileBoundsForSection(stackId, sectionId, null);

        assertNotNull(list, "null list retrieved");
        assertEquals(2, list.size(), "invalid number of tiles found");
    }

    @Test
    public void testGetSectionDataForZ() {
        final Double z = 3903.0;
        final List<SectionData> list = dao.getSectionDataForZ(stackId, z);

        assertNotNull(list, "null list retrieved");
        assertEquals(2, list.size(),
                     "invalid number of sections found, actual values were " + list);
        assertEquals("3903.0", list.get(0).getSectionId(),
                     "invalid first section id");
        assertEquals("mis-ordered-section", list.get(1).getSectionId(),
                     "invalid second section id");
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

        assertEquals(worldCoordinates.size(), worldCoordinatesWithTileIds.size(),
                     "invalid number of lists returned");

        // first coordinate
        List<TileCoordinates> tileCoordinatesList = worldCoordinatesWithTileIds.getFirst();

        assertEquals(1, tileCoordinatesList.size(),
                     "invalid number of tiles found for first coordinate");

        TileCoordinates tileCoordinates = tileCoordinatesList.getFirst();

        assertEquals("134", tileCoordinates.getTileId(),
                     "invalid tileId for first coordinate");

        // second coordinate
        tileCoordinatesList = worldCoordinatesWithTileIds.get(1);

        assertEquals(2, tileCoordinatesList.size(),
                     "invalid number of tiles found for second coordinate");

        tileCoordinates = tileCoordinatesList.get(0);

        assertEquals("134", tileCoordinates.getTileId(),
                     "invalid tileId for second coordinate, first tile");

        tileCoordinates = tileCoordinatesList.get(1);

        assertEquals("171", tileCoordinates.getTileId(),
                     "invalid tileId for second coordinate, second tile");
    }

    @Test
    public void testGetResolvedTiles() {
        final Double z = 3903.0;

        ResolvedTileSpecCollection resolvedTiles = dao.getResolvedTiles(stackId, z, null);
        assertNotNull(resolvedTiles, "null collection retrieved for z query");
        assertEquals(12, resolvedTiles.getTileCount(), "invalid number of tiles found for z query");

        resolvedTiles = dao.getResolvedTiles(stackId, null, null, groupId, null, null, null, null, null);
        assertNotNull(resolvedTiles, "null collection retrieved for groupId query");
        assertEquals(3, resolvedTiles.getTileCount(), "invalid number of tiles found for groupId query");


        resolvedTiles = dao.getResolvedTiles(stackId, null, null, groupId, 3950.0, null, null, null, null);
        assertNotNull(resolvedTiles, "null collection retrieved for groupId with minX query");
        assertEquals(1, resolvedTiles.getTileCount(),
                     "invalid number of tiles found for groupId with minX query");

        resolvedTiles = dao.getResolvedTiles(stackId, 3903.0, null, null, null, null, null, null, null);
        assertNotNull(resolvedTiles, "null collection retrieved for min z query");
        assertEquals(14, resolvedTiles.getTileCount(), "invalid number of tiles found for min z query");

        resolvedTiles = dao.getResolvedTiles(stackId, null, 3903.0, null, null, null, null, null, null);
        assertNotNull(resolvedTiles, "null collection retrieved for max z query");
        assertEquals(12, resolvedTiles.getTileCount(), "invalid number of tiles found for max z query");

        resolvedTiles = dao.getResolvedTiles(stackId, 3903.1, 3905.0, null, null, null, null, null, null);
        assertNotNull(resolvedTiles, "null collection retrieved for min/max z query");
        assertEquals(2, resolvedTiles.getTileCount(), "invalid number of tiles found for min/max z query");

    }

    @Test
    public void testWriteTileIds() throws Exception {

        final String[] matchPatterns =           { null, "with-real" };
        final int[] expectedMatchingTileCounts = {   14,           2 };

        for (int test = 0; test < matchPatterns.length; test++) {
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);
            dao.writeTileIds(stackId, null, null, matchPatterns[test], outputStream);
            final String[] tileIds = outputStream.toString().split(",");
            assertEquals(expectedMatchingTileCounts[test], tileIds.length,
                         "invalid number of tileIds written for query test " + test);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDaoReadOnlyTest.class);
    private static final Double BOUNDS_DELTA = 0.1;
    private static final String groupId = "A";
}
