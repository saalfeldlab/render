package org.janelia.render.service.dao;

import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.LOADING;

/**
 * Tests the {@link RenderDao} class.
 *
 * @author Eric Trautman
 */
public class RenderDaoTest {

    private static StackId stackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDao dao;

    @BeforeClass
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());
    }

    @Before
    public void setUp() throws Exception {
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
    public void testGetStackMetaDataListForOwner() throws Exception {
        final List<StackMetaData> list = dao.getStackMetaDataListForOwner(stackId.getOwner());

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of stacks found", 2, list.size());
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
    public void testCloneStack() throws Exception {

        final StackId toStackId = new StackId(stackId.getOwner(), stackId.getProject(), "clonedStack");

        StackMetaData toStackMetaData = dao.getStackMetaData(toStackId);
        Assert.assertNull("stack should not exist before clone", toStackMetaData);

        List<Double> zValues = dao.getZValues(toStackId);
        Assert.assertEquals("no z values should exist before clone", 0, zValues.size());

        StackMetaData fromStackMetaData = dao.getStackMetaData(stackId);
        fromStackMetaData = dao.ensureIndexesAndDeriveStats(fromStackMetaData);

        dao.cloneStack(stackId, toStackId);

        zValues = dao.getZValues(toStackId);
        Assert.assertEquals("invalid number of z values after clone", 1, zValues.size());

        toStackMetaData = new StackMetaData(toStackId, fromStackMetaData.getCurrentVersion());
        toStackMetaData = dao.ensureIndexesAndDeriveStats(toStackMetaData);

        final StackStats fromStats = fromStackMetaData.getStats();
        Assert.assertNotNull("null fromStats", fromStats);

        final StackStats toStats = toStackMetaData.getStats();
        Assert.assertNotNull("null toStats", toStats);

        Assert.assertEquals("cloned tile count does not match",
                            fromStats.getTileCount(), toStats.getTileCount());
        Assert.assertEquals("cloned transform count does not match",
                            fromStats.getTransformCount(), toStats.getTransformCount());
    }

    @Test
    public void testSaveStackMetaDataAndDeriveStats() throws Exception {

        final StackVersion secondTry = new StackVersion(new Date(),
                                                        "second try",
                                                        5,
                                                        3,
                                                        4.2,
                                                        4.2,
                                                        35.2,
                                                        null,
                                                        null);

        final StackMetaData stackMetaDataBeforeSave = dao.getStackMetaData(stackId);
        final StackMetaData updatedStackMetaData = stackMetaDataBeforeSave.getNextVersion(secondTry);
        dao.saveStackMetaData(updatedStackMetaData);

        final StackMetaData stackMetaDataAfterSave = dao.getStackMetaData(stackId);
        validateStackMetaData(" after save", LOADING, 3, secondTry, stackMetaDataAfterSave);

        dao.ensureIndexesAndDeriveStats(stackMetaDataAfterSave);

        final StackMetaData stackMetaDataAfterStats = dao.getStackMetaData(stackId);

        final StackStats stats = stackMetaDataAfterStats.getStats();
        Assert.assertNotNull("null stats returned after derivation", stats);

        final Bounds expectedBounds = new Bounds(1094.0, 1769.0, 3903.0, 9917.0, 8301.0, 3903.0);

        Assert.assertEquals("invalid bounds", expectedBounds.toJson(), stats.getStackBounds().toJson());
        Assert.assertEquals("invalid tile count", new Long(12), stats.getTileCount());

    }

    @Test
    public void testRemoveStack() throws Exception {

        final StackMetaData stackMetaBeforeRemove = dao.getStackMetaData(stackId);
        Assert.assertNotNull("meta data for " + stackId + " missing before removal", stackMetaBeforeRemove);

        final List<Double> zValuesBeforeRemove = dao.getZValues(stackId);
        Assert.assertNotNull("zValues null for " + stackId + " before removal",
                             zValuesBeforeRemove);
        Assert.assertTrue("zValues missing for " + stackId + " before removal",
                          zValuesBeforeRemove.size() > 0);

        dao.removeStack(stackId, true);
        final StackMetaData stackMetaAfterRemove = dao.getStackMetaData(stackId);

        Assert.assertNull("meta data for " + stackId + " returned after removal", stackMetaAfterRemove);

        final List<Double> zValuesAfterRemove = dao.getZValues(stackId);
        Assert.assertNotNull("zValues null for " + stackId + " after removal",
                             zValuesAfterRemove);
        Assert.assertEquals("zValues exist for " + stackId + " after removal",
                            0, zValuesAfterRemove.size());
    }

    @Test
    public void testGetParameters() throws Exception {

        final Double x = 1000.0;
        final Double y = 3000.0;
        final Double z = 3903.0;
        final Integer width = 5000;
        final Integer height = 2000;
        final Double scale = 0.5;

        final RenderParameters parameters = dao.getParameters(stackId, x, y, z, width, height, scale);

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
        final List<TileSpec> tileSpecs = parameters.getTileSpecs();
        Assert.assertNotNull("null tile specs value after init", tileSpecs);
        Assert.assertEquals("invalid number of tiles after init", 6, tileSpecs.size());

        ListTransformSpec transforms;
        for (final TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            Assert.assertTrue("tileSpec " + tileSpec.getTileId() + " is not fully resolved",
                              transforms.isFullyResolved());
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

    @Test
    public void testSaveTileSpec() throws Exception {
        final String tileId = "new-tile-1";
        final String temca = "0";
        final LayoutData layoutData = new LayoutData("s123", temca, null, null, null, null, null, null);

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);
        tileSpec.setLayout(layoutData);

        dao.saveTileSpec(stackId, tileSpec);

        final TileSpec insertedTileSpec = dao.getTileSpec(stackId, tileId, false);

        Assert.assertNotNull("null tileSpec retrieved after insert", insertedTileSpec);
        final LayoutData insertedLayoutData = insertedTileSpec.getLayout();
        Assert.assertNotNull("null layout retrieved after insert", insertedLayoutData);
        Assert.assertEquals("invalid temca retrieved after insert", temca, insertedLayoutData.getTemca());
        Assert.assertFalse("tileSpec is has transforms after insert", tileSpec.hasTransforms());

        final String changedTemca = "1";
        final LayoutData changedLayoutData = new LayoutData("s123", changedTemca, null, null, null, null, null, null);
        tileSpec.setLayout(changedLayoutData);
        final List<TransformSpec> list = new ArrayList<>();
        list.add(new ReferenceTransformSpec("1"));
        tileSpec.addTransformSpecs(list);

        dao.saveTileSpec(stackId, tileSpec);

        final TileSpec updatedTileSpec = dao.getTileSpec(stackId, tileId, false);

        Assert.assertNotNull("null tileSpec retrieved after update", updatedTileSpec);
        final LayoutData updatedLayoutData = updatedTileSpec.getLayout();
        Assert.assertNotNull("null layout retrieved after update", updatedLayoutData);
        Assert.assertEquals("invalid temca retrieved after update", changedTemca, updatedLayoutData.getTemca());
        Assert.assertTrue("tileSpec is missing transforms after update", tileSpec.hasTransforms());
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
        Assert.assertEquals("invalid type retrieved", "list", transformSpec.getType());
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTransformSpecWithBadId() throws Exception {
        dao.getTransformSpec(stackId, "missingId");
    }

    @Test
    public void testSaveTransformSpec() throws Exception {

        final String transformId = "new-transform-1";
        final TransformSpecMetaData metaData = new TransformSpecMetaData();
        metaData.setGroup("test-group");

        final LeafTransformSpec leafSpec = new LeafTransformSpec(transformId,
                                                                 metaData,
                                                                 AffineModel2D.class.getName(),
                                                                 "1  0  0  1  0  0");
        dao.saveTransformSpec(stackId, leafSpec);

        final TransformSpec insertedSpec = dao.getTransformSpec(stackId, transformId);

        Assert.assertNotNull("null transformSpec retrieved after insert", insertedSpec);
        final TransformSpecMetaData insertedMetaData = insertedSpec.getMetaData();
        Assert.assertNotNull("null meta data retrieved after insert", insertedMetaData);
        Assert.assertEquals("invalid group retrieved after insert",
                            metaData.getGroup(), insertedMetaData.getGroup());

        final TransformSpecMetaData changedMetaData = new TransformSpecMetaData();
        changedMetaData.setGroup("updated-group");

        final ListTransformSpec listSpec = new ListTransformSpec(transformId, changedMetaData);
        listSpec.addSpec(new ReferenceTransformSpec("1"));

        dao.saveTransformSpec(stackId, listSpec);

        final TransformSpec updatedSpec = dao.getTransformSpec(stackId, transformId);

        Assert.assertNotNull("null transformSpec retrieved after update", updatedSpec);
        final TransformSpecMetaData updatedMetaData = updatedSpec.getMetaData();
        Assert.assertNotNull("null meta data retrieved after update", updatedMetaData);
        Assert.assertEquals("invalid group retrieved after update",
                            changedMetaData.getGroup(), updatedMetaData.getGroup());
        Assert.assertFalse("transformSpec should not be resolved after update", updatedSpec.isFullyResolved());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveTransformSpecWithBadTransformReference() throws Exception {
        final ListTransformSpec listSpec = new ListTransformSpec("bad-ref-transform", null);
        listSpec.addSpec(new ReferenceTransformSpec("missing-id"));

        dao.saveTransformSpec(stackId, listSpec);
    }

    @Test
    public void testGetZValues() throws Exception {
        final List<Double> list = dao.getZValues(stackId);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of layers found", 1, list.size());
    }

    @Test
    public void testGetLayerBounds() throws Exception {
        final Double expectedMinX = 1094.0;
        final Double expectedMinY = 1769.0;
        final Double expectedMaxX = 9917.0;
        final Double expectedMaxY = 8301.0;

        final Double z = 3903.0;

        Bounds bounds = dao.getLayerBounds(stackId, z);

        Assert.assertNotNull("null layer bounds retrieved", bounds);
        Assert.assertEquals("invalid layer minX", expectedMinX, bounds.getMinX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer minY", expectedMinY, bounds.getMinY(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer minZ", z, bounds.getMinZ(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxX", expectedMaxX, bounds.getMaxX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxY", expectedMaxY, bounds.getMaxY(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxZ", z, bounds.getMaxZ(), BOUNDS_DELTA);
    }

    @Test
    public void testGetTileBounds() throws Exception {
        final Double z = 3903.0;
        final List<TileBounds> list = dao.getTileBounds(stackId, z);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of tiles found", 12, list.size());

        final TileBounds firstTileBounds = list.get(0);

        Assert.assertNotNull("null bounds for first tile", firstTileBounds);
        Assert.assertEquals("incorrect id for first tile", "134", firstTileBounds.getTileId());
        Assert.assertTrue("bound box not defined for first tile", firstTileBounds.isBoundingBoxDefined());
    }

    @Test
    public void testWriteCoordinatesWithTileIds() throws Exception {
        final Double z = 3903.0;
        final List<TileCoordinates> worldCoordinates = new ArrayList<>();
        worldCoordinates.add(TileCoordinates.buildWorldInstance(null, new double[] {1900, 3000}));
        worldCoordinates.add(TileCoordinates.buildWorldInstance(null, new double[] {3700, 3000}));
        worldCoordinates.add(TileCoordinates.buildWorldInstance(null, new double[]{4500, 3000}));

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeCoordinatesWithTileIds(stackId, z, worldCoordinates, outputStream);

        final String json = outputStream.toString();
        final Type typeOfT = new TypeToken<List<List<TileCoordinates>>>(){}.getType();
        final List<List<TileCoordinates>> worldCoordinatesWithTileIds = JsonUtils.GSON.fromJson(json, typeOfT);

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

    public static void validateStackMetaData(String context,
                                             StackMetaData.StackState expectedState,
                                             Integer expectedVersionNumber,
                                             StackVersion expectedVersion,
                                             StackMetaData actualMetaData) {

        Assert.assertNotNull("null meta data retrieved" + context, actualMetaData);
        Assert.assertEquals("invalid state" + context,
                            expectedState, actualMetaData.getState());
        Assert.assertNotNull("null modified date" + context,
                             actualMetaData.getLastModifiedTimestamp());
        Assert.assertEquals("invalid version number" + context,
                            expectedVersionNumber, actualMetaData.getCurrentVersionNumber());

        final StackVersion actualVersion = actualMetaData.getCurrentVersion();
        Assert.assertNotNull("null version for " + context, actualVersion);
        Assert.assertEquals("invalid createTimestamp" + context,
                            expectedVersion.getCreateTimestamp(), actualVersion.getCreateTimestamp());
        Assert.assertEquals("invalid versionNotes" + context,
                            expectedVersion.getVersionNotes(), actualVersion.getVersionNotes());
        Assert.assertEquals("invalid cycleNumber" + context,
                            expectedVersion.getCycleNumber(), actualVersion.getCycleNumber());
        Assert.assertEquals("invalid cycleStepNumber" + context,
                            expectedVersion.getCycleStepNumber(), actualVersion.getCycleStepNumber());
        Assert.assertEquals("invalid stackResolutionX" + context,
                            expectedVersion.getStackResolutionX(), actualVersion.getStackResolutionX());
        Assert.assertEquals("invalid stackResolutionY" + context,
                            expectedVersion.getStackResolutionY(), actualVersion.getStackResolutionY());
        Assert.assertEquals("invalid stackResolutionZ" + context,
                            expectedVersion.getStackResolutionZ(), actualVersion.getStackResolutionZ());
        Assert.assertEquals("invalid snapshotRootPath" + context,
                            expectedVersion.getSnapshotRootPath(), actualVersion.getSnapshotRootPath());
        Assert.assertEquals("invalid mipmapMetaData" + context,
                            expectedVersion.getMipmapMetaData(), actualVersion.getMipmapMetaData());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDaoTest.class);
    private static final Double BOUNDS_DELTA = 0.1;
}
