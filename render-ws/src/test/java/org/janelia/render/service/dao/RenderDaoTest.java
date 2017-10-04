package org.janelia.render.service.dao;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
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

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.LOADING;

/**
 * Tests {@link RenderDao} methods that change persisted data.
 * The embedded database is rebuilt for each test, so these tests take longer to run.
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
    public void testRenameStack() throws Exception {

        StackId fromStackId = stackId;
        final List<Double> fromZValues = dao.getZValues(fromStackId);

        // -------------------------------------------------------------------------
        // test renaming stack without stats ...

        StackId toStackId = new StackId(fromStackId.getOwner(), fromStackId.getProject(), "renamedStackA");

        StackMetaData toStackMetaData = dao.getStackMetaData(toStackId);
        Assert.assertNull("toStack should not exist before rename", toStackMetaData);

        dao.renameStack(fromStackId, toStackId);

        toStackMetaData = dao.getStackMetaData(toStackId);
        Assert.assertNotNull("toStack should exist after rename", toStackMetaData);

        StackMetaData fromStackMetaData = dao.getStackMetaData(fromStackId);
        Assert.assertNull("fromStack should not exist after rename", fromStackMetaData);

        List<Double> toZValues = dao.getZValues(toStackId);
        Assert.assertArrayEquals("z values do not match after rename", fromZValues.toArray(), toZValues.toArray());

        // -------------------------------------------------------------------------
        // test renaming stack with stats ...

        fromStackId = toStackId;
        fromStackMetaData = toStackMetaData;
        fromStackMetaData = dao.ensureIndexesAndDeriveStats(fromStackMetaData);
        final StackStats fromStats = fromStackMetaData.getStats();

        toStackId = new StackId(fromStackId.getOwner(), fromStackId.getProject(), "renamedStackB");

        toStackMetaData = dao.getStackMetaData(toStackId);
        Assert.assertNull("toStack should not exist before rename", toStackMetaData);

        dao.renameStack(fromStackId, toStackId);

        toStackMetaData = dao.getStackMetaData(toStackId);
        Assert.assertNotNull("toStack should exist after rename", toStackMetaData);

        fromStackMetaData = dao.getStackMetaData(fromStackId);
        Assert.assertNull("fromStack should not exist after rename", fromStackMetaData);

        toZValues = dao.getZValues(toStackId);
        Assert.assertArrayEquals("z values do not match after rename", fromZValues.toArray(), toZValues.toArray());

        final StackStats toStats = toStackMetaData.getStats();

        Assert.assertEquals("incorrect stats after rename", fromStats.toJson(), toStats.toJson());
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

        dao.cloneStack(stackId, toStackId, null, null);

        zValues = dao.getZValues(toStackId);
        Assert.assertEquals("invalid number of z values after clone", 2, zValues.size());

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

        final Double newZValue = 999.0;
        final TileSpec newTileSpec = new TileSpec();
        newTileSpec.setTileId("new-tile-spec");
        newTileSpec.setZ(newZValue);

        dao.saveTileSpec(stackId, newTileSpec);

        final StackId filteredStackId = new StackId(stackId.getOwner(), stackId.getProject(), "filteredStack");
        final List<Double> filteredZValues = new ArrayList<>();
        filteredZValues.add(newZValue);
        dao.cloneStack(stackId, filteredStackId, filteredZValues, null);

        zValues = dao.getZValues(filteredStackId);
        Assert.assertEquals("invalid number of z values after clone filter", 1, zValues.size());
        Assert.assertEquals("invalid z value after clone filter", newZValue, zValues.get(0));
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

        final Bounds expectedBounds = new Bounds(1094.0, 1769.0, 3903.0, 9917.0, 8301.0, 3903.1);

        Assert.assertEquals("invalid bounds", expectedBounds.toJson(), stats.getStackBounds().toJson());
        Assert.assertEquals("invalid tile count", new Long(14), stats.getTileCount());

        // test getSectionData after section collection was created by call to ensureIndexesAndDeriveStats
        final List<SectionData> list = dao.getSectionData(stackId, null, null);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of sections found", 3, list.size());
        final SectionData sectionData = list.get(0);
        Assert.assertEquals("invalid sectionId for first section", "3903.0", sectionData.getSectionId());
        Assert.assertEquals("invalid z for section 3903.0", 3903, sectionData.getZ(), 0.01);
        Assert.assertEquals("invalid tileCount for section 3903.0", new Long(2), sectionData.getTileCount());

        final List<SectionData> filteredList = dao.getSectionData(stackId, 3902.0, 3903.0);

        Assert.assertNotNull("null filtered list retrieved", filteredList);
        Assert.assertEquals("invalid number of sections found for filtered list", 2, filteredList.size());
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
    public void testRemoveTilesWithSectionId() throws Exception {

        final Double z = 3903.0;
        final List<TileBounds> tileBoundsBeforeRemove = dao.getTileBoundsForZ(stackId, z);

        Assert.assertNotNull("tileBoundsBeforeRemove null for " + stackId + " before removal",
                             tileBoundsBeforeRemove);

        dao.removeTilesWithSectionId(stackId, "mis-ordered-section");

        final List<TileBounds> tileBoundsAfterRemove = dao.getTileBoundsForZ(stackId, z);

        Assert.assertNotNull("tileBoundsAfterRemove null for " + stackId + " after removal",
                             tileBoundsAfterRemove);
        Assert.assertEquals("invalid tile count after section removal (only one tile should be removed)",
                            (tileBoundsBeforeRemove.size() - 1), tileBoundsAfterRemove.size());
    }

    @Test
    public void testRemoveTilesWithIds() throws Exception {

        final Double z = 3903.0;
        final List<TileBounds> tileBoundsBeforeRemove = dao.getTileBoundsForZ(stackId, z);

        dao.removeTilesWithIds(stackId, Arrays.asList("134", "135", "136"));

        final List<TileBounds> tileBoundsAfterRemove = dao.getTileBoundsForZ(stackId, z);

        Assert.assertEquals("invalid tile count after tile list removal",
                            (tileBoundsBeforeRemove.size() - 3), tileBoundsAfterRemove.size());
    }

    @Test
    public void testRemoveTile() throws Exception {

        final Double z = 3903.0;
        final List<TileBounds> tileBoundsBeforeRemove = dao.getTileBoundsForZ(stackId, z);

        dao.removeTile(stackId, "134");

        final List<TileBounds> tileBoundsAfterRemove = dao.getTileBoundsForZ(stackId, z);

        Assert.assertEquals("invalid tile count after tile removal",
                            (tileBoundsBeforeRemove.size() - 1), tileBoundsAfterRemove.size());
    }

    @Test
    public void testRemoveTilesWithZ() throws Exception {

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId("testTileId");
        tileSpec.setZ(999.0);

        dao.saveTileSpec(stackId, tileSpec);

        final List<Double> zValuesBeforeRemove = dao.getZValues(stackId);
        Assert.assertNotNull("zValues null for " + stackId + " before removal",
                             zValuesBeforeRemove);
        Assert.assertEquals("incorrect number of zValues for " + stackId + " before removal",
                            3, zValuesBeforeRemove.size());

        dao.removeTilesWithZ(stackId, tileSpec.getZ());

        final List<Double> zValuesAfterRemove = dao.getZValues(stackId);
        Assert.assertNotNull("zValues null for " + stackId + " after removal",
                             zValuesAfterRemove);
        Assert.assertEquals("zValues exist for " + stackId + " after removal",
                            2, zValuesAfterRemove.size());
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

    @Test
    public void testSaveTransformSpec() throws Exception {

        final String transformId = "new-transform-1";
        final String testGroupLabel = "test-group";
        final TransformSpecMetaData metaData = new TransformSpecMetaData();

        final LeafTransformSpec leafSpec = new LeafTransformSpec(transformId,
                                                                 metaData,
                                                                 AffineModel2D.class.getName(),
                                                                 "1  0  0  1  0  0");
        leafSpec.addLabel(testGroupLabel);
        dao.saveTransformSpec(stackId, leafSpec);

        final TransformSpec insertedSpec = dao.getTransformSpec(stackId, transformId);

        Assert.assertNotNull("null transformSpec retrieved after insert", insertedSpec);
        Assert.assertTrue("label missing after insert", insertedSpec.hasLabel(testGroupLabel));

        insertedSpec.removeLabel(testGroupLabel);
        Assert.assertFalse("label exists after removal", insertedSpec.hasLabel(testGroupLabel));

        final ListTransformSpec listSpec = new ListTransformSpec(transformId, null);
        listSpec.addSpec(new ReferenceTransformSpec("1"));

        dao.saveTransformSpec(stackId, listSpec);

        final TransformSpec updatedSpec = dao.getTransformSpec(stackId, transformId);

        Assert.assertNotNull("null transformSpec retrieved after update", updatedSpec);
        Assert.assertFalse("transformSpec should not be resolved after update", updatedSpec.isFullyResolved());
    }

    @Test
    public void testUpdateZForSection() throws Exception {

        final String sectionId = "mis-ordered-section";
        final Double zBeforeUpdate = dao.getZForSection(stackId, sectionId);

        Assert.assertEquals("incorrect z before update", 3903.0, zBeforeUpdate, 0.1);

        final Double updatedZ = 999.0;
        dao.updateZForSection(stackId, "mis-ordered-section", updatedZ);

        final Double zAfterUpdate = dao.getZForSection(stackId, sectionId);

        Assert.assertEquals("incorrect z before update", updatedZ, zAfterUpdate, 0.1);
    }

    @Test
    public void testUpdateZForTiles() throws Exception {
        final String tileIdA = "134";
        final String tileIdB = "135";

        final List<String> tileIds = Arrays.asList(tileIdA, tileIdB);

        final Double zBeforeUpdateA = dao.getTileSpec(stackId, tileIdA, false).getZ();
        final Double zBeforeUpdateB = dao.getTileSpec(stackId, tileIdB, false).getZ();

        final Double updatedZ = 999.0;

        Assert.assertNotSame("z for tile '" + tileIdA + "' should differ from update value",
                             updatedZ, zBeforeUpdateA);
        Assert.assertNotSame("z for tile '" + tileIdB + "' should differ from update value",
                             updatedZ, zBeforeUpdateB);

        dao.updateZForTiles(stackId, updatedZ, tileIds);

        final Double zAfterUpdateA = dao.getTileSpec(stackId, tileIdA, false).getZ();
        final Double zAfterUpdateB = dao.getTileSpec(stackId, tileIdB, false).getZ();

        Assert.assertEquals("z not updated for tile '" + tileIdA + "'", updatedZ, zAfterUpdateA);
        Assert.assertEquals("z not updated for tile '" + tileIdB + "'", updatedZ, zAfterUpdateB);
    }

    public static void validateStackMetaData(final String context,
                                             final StackMetaData.StackState expectedState,
                                             final Integer expectedVersionNumber,
                                             final StackVersion expectedVersion,
                                             final StackMetaData actualMetaData) {

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
        Assert.assertEquals("invalid mipmapPathBuilder" + context,
                            expectedVersion.getMipmapPathBuilder(), actualVersion.getMipmapPathBuilder());
    }

}
