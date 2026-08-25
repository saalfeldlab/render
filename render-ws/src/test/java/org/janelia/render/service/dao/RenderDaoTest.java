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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.LOADING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @BeforeAll
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());
    }

    @BeforeEach
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

    @AfterAll
    public static void after() {
        embeddedMongoDb.stop();
    }

    @Test
    public void testRenameStack() {

        StackId fromStackId = stackId;
        final List<Double> fromZValues = dao.getZValues(fromStackId);

        // -------------------------------------------------------------------------
        // test renaming stack without stats ...

        StackId toStackId = new StackId(fromStackId.getOwner(), fromStackId.getProject(), "renamedStackA");

        StackMetaData toStackMetaData = dao.getStackMetaData(toStackId);
        assertNull(toStackMetaData, "toStack should not exist before rename");

        dao.renameStack(fromStackId, toStackId);

        toStackMetaData = dao.getStackMetaData(toStackId);
        assertNotNull(toStackMetaData, "toStack should exist after rename");

        StackMetaData fromStackMetaData = dao.getStackMetaData(fromStackId);
        assertNull(fromStackMetaData, "fromStack should not exist after rename");

        List<Double> toZValues = dao.getZValues(toStackId);
        assertArrayEquals(fromZValues.toArray(), toZValues.toArray(), "z values do not match after rename");

        // -------------------------------------------------------------------------
        // test renaming stack with stats ...

        fromStackId = toStackId;
        fromStackMetaData = toStackMetaData;
        fromStackMetaData = dao.ensureIndexesAndDeriveStats(fromStackMetaData);
        final StackStats fromStats = fromStackMetaData.getStats();

        toStackId = new StackId(fromStackId.getOwner(), fromStackId.getProject(), "renamedStackB");

        toStackMetaData = dao.getStackMetaData(toStackId);
        assertNull(toStackMetaData, "toStack should not exist before rename");

        dao.renameStack(fromStackId, toStackId);

        toStackMetaData = dao.getStackMetaData(toStackId);
        assertNotNull(toStackMetaData, "toStack should exist after rename");

        fromStackMetaData = dao.getStackMetaData(fromStackId);
        assertNull(fromStackMetaData, "fromStack should not exist after rename");

        toZValues = dao.getZValues(toStackId);
        assertArrayEquals(fromZValues.toArray(), toZValues.toArray(), "z values do not match after rename");

        final StackStats toStats = toStackMetaData.getStats();

        assertEquals(fromStats.toJson(), toStats.toJson(), "incorrect stats after rename");
    }

    @Test
    public void testCloneStack() {

        final StackId toStackId = new StackId(stackId.getOwner(), stackId.getProject(), "clonedStack");

        StackMetaData toStackMetaData = dao.getStackMetaData(toStackId);
        assertNull(toStackMetaData, "stack should not exist before clone");

        List<Double> zValues = dao.getZValues(toStackId);
        assertEquals(0, zValues.size(), "no z values should exist before clone");

        StackMetaData fromStackMetaData = dao.getStackMetaData(stackId);
        fromStackMetaData = dao.ensureIndexesAndDeriveStats(fromStackMetaData);

        dao.cloneStack(stackId, toStackId, null, null);

        zValues = dao.getZValues(toStackId);
        assertEquals(2, zValues.size(), "invalid number of z values after clone");

        toStackMetaData = new StackMetaData(toStackId, fromStackMetaData.getCurrentVersion());
        toStackMetaData = dao.ensureIndexesAndDeriveStats(toStackMetaData);

        final StackStats fromStats = fromStackMetaData.getStats();
        assertNotNull(fromStats, "null fromStats");

        final StackStats toStats = toStackMetaData.getStats();
        assertNotNull(toStats, "null toStats");

        assertEquals(fromStats.tileCount(), toStats.tileCount(),
                     "cloned tile count does not match");
        assertEquals(fromStats.transformCount(), toStats.transformCount(),
                     "cloned transform count does not match");

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
        assertEquals(1, zValues.size(), "invalid number of z values after clone filter");
        assertEquals(newZValue, zValues.getFirst(), "invalid z value after clone filter");
    }

    @Test
    public void testSaveStackMetaDataAndDeriveStats() {

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
        assertNotNull(stats, "null stats returned after derivation");

        final Bounds expectedBounds = new Bounds(1094.0, 1769.0, 3903.0, 9917.0, 8301.0, 3903.1);

        assertEquals(expectedBounds.toJson(), stats.stackBounds().toJson(), "invalid bounds");
        assertEquals(new Long(14), stats.tileCount(), "invalid tile count");

        // test getSectionData after section collection was created by call to ensureIndexesAndDeriveStats
        final List<SectionData> list = dao.getSectionData(stackId, null, null);

        assertNotNull(list, "null list retrieved");
        assertEquals(3, list.size(), "invalid number of sections found");
        final SectionData sectionData = list.getFirst();
        assertEquals("3903.0", sectionData.getSectionId(), "invalid sectionId for first section");
        assertEquals(3903, sectionData.getZ(), 0.01, "invalid z for section 3903.0");
        assertEquals(new Long(2), sectionData.getTileCount(), "invalid tileCount for section 3903.0");

        final List<SectionData> filteredList = dao.getSectionData(stackId, 3902.0, 3903.0);

        assertNotNull(filteredList, "null filtered list retrieved");
        assertEquals(2, filteredList.size(), "invalid number of sections found for filtered list");
    }

    @Test
    public void testRemoveStack() {

        final StackMetaData stackMetaBeforeRemove = dao.getStackMetaData(stackId);
        assertNotNull(stackMetaBeforeRemove, "meta data for " + stackId + " missing before removal");

        final List<Double> zValuesBeforeRemove = dao.getZValues(stackId);
        assertNotNull(zValuesBeforeRemove,
                      "zValues null for " + stackId + " before removal");
        assertTrue(zValuesBeforeRemove.size() > 0,
                   "zValues missing for " + stackId + " before removal");

        dao.removeStack(stackId, true);
        final StackMetaData stackMetaAfterRemove = dao.getStackMetaData(stackId);

        assertNull(stackMetaAfterRemove, "meta data for " + stackId + " returned after removal");

        final List<Double> zValuesAfterRemove = dao.getZValues(stackId);
        assertNotNull(zValuesAfterRemove,
                      "zValues null for " + stackId + " after removal");
        assertEquals(0, zValuesAfterRemove.size(),
                     "zValues exist for " + stackId + " after removal");
    }

    @Test
    public void testRemoveTilesWithSectionId() {

        final Double z = 3903.0;
        final List<TileBounds> tileBoundsBeforeRemove = dao.getTileBoundsForZ(stackId, z, null);

        assertNotNull(tileBoundsBeforeRemove,
                      "tileBoundsBeforeRemove null for " + stackId + " before removal");

        dao.removeTilesWithSectionId(stackId, "mis-ordered-section");

        final List<TileBounds> tileBoundsAfterRemove = dao.getTileBoundsForZ(stackId, z, null);

        assertNotNull(tileBoundsAfterRemove,
                      "tileBoundsAfterRemove null for " + stackId + " after removal");
        assertEquals((tileBoundsBeforeRemove.size() - 1), tileBoundsAfterRemove.size(),
                     "invalid tile count after section removal (only one tile should be removed)");
    }

    @Test
    public void testRemoveTilesWithIds() {

        final Double z = 3903.0;
        final List<TileBounds> tileBoundsBeforeRemove = dao.getTileBoundsForZ(stackId, z, null);

        dao.removeTilesWithIds(stackId, Arrays.asList("134", "135", "136"));

        final List<TileBounds> tileBoundsAfterRemove = dao.getTileBoundsForZ(stackId, z, null);

        assertEquals((tileBoundsBeforeRemove.size() - 3), tileBoundsAfterRemove.size(),
                     "invalid tile count after tile list removal");
    }

    @Test
    public void testRemoveTile() {

        final Double z = 3903.0;
        final List<TileBounds> tileBoundsBeforeRemove = dao.getTileBoundsForZ(stackId, z, null);

        dao.removeTile(stackId, "134");

        final List<TileBounds> tileBoundsAfterRemove = dao.getTileBoundsForZ(stackId, z, null);

        assertEquals((tileBoundsBeforeRemove.size() - 1), tileBoundsAfterRemove.size(),
                     "invalid tile count after tile removal");
    }

    @Test
    public void testRemoveTilesWithZ() {

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId("testTileId");
        tileSpec.setZ(999.0);

        dao.saveTileSpec(stackId, tileSpec);

        final List<Double> zValuesBeforeRemove = dao.getZValues(stackId);
        assertNotNull(zValuesBeforeRemove,
                      "zValues null for " + stackId + " before removal");
        assertEquals(3, zValuesBeforeRemove.size(),
                     "incorrect number of zValues for " + stackId + " before removal");

        dao.removeTilesWithZ(stackId, tileSpec.getZ());

        final List<Double> zValuesAfterRemove = dao.getZValues(stackId);
        assertNotNull(zValuesAfterRemove,
                      "zValues null for " + stackId + " after removal");
        assertEquals(2, zValuesAfterRemove.size(),
                     "zValues exist for " + stackId + " after removal");
    }

    @Test
    public void testSaveTileSpec() {
        final String tileId = "new-tile-1";
        final String temca = "0";
        final LayoutData layoutData = new LayoutData("s123", temca, null, null, null, null, null, null);

        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);
        tileSpec.setLayout(layoutData);

        dao.saveTileSpec(stackId, tileSpec);

        final TileSpec insertedTileSpec = dao.getTileSpec(stackId, tileId, false);

        assertNotNull(insertedTileSpec, "null tileSpec retrieved after insert");
        final LayoutData insertedLayoutData = insertedTileSpec.getLayout();
        assertNotNull(insertedLayoutData, "null layout retrieved after insert");
        assertEquals(temca, insertedLayoutData.getTemca(), "invalid temca retrieved after insert");
        assertFalse(tileSpec.hasTransforms(), "tileSpec is has transforms after insert");

        final String changedTemca = "1";
        final LayoutData changedLayoutData = new LayoutData("s123", changedTemca, null, null, null, null, null, null);
        tileSpec.setLayout(changedLayoutData);
        final List<TransformSpec> list = new ArrayList<>();
        list.add(new ReferenceTransformSpec("1"));
        tileSpec.addTransformSpecs(list);

        dao.saveTileSpec(stackId, tileSpec);

        final TileSpec updatedTileSpec = dao.getTileSpec(stackId, tileId, false);

        assertNotNull(updatedTileSpec, "null tileSpec retrieved after update");
        final LayoutData updatedLayoutData = updatedTileSpec.getLayout();
        assertNotNull(updatedLayoutData, "null layout retrieved after update");
        assertEquals(changedTemca, updatedLayoutData.getTemca(), "invalid temca retrieved after update");
        assertTrue(tileSpec.hasTransforms(), "tileSpec is missing transforms after update");
    }

    @Test
    public void testSaveTransformSpec() {

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

        assertNotNull(insertedSpec, "null transformSpec retrieved after insert");
        assertTrue(insertedSpec.hasLabel(testGroupLabel), "label missing after insert");

        insertedSpec.removeLabel(testGroupLabel);
        assertFalse(insertedSpec.hasLabel(testGroupLabel), "label exists after removal");

        final ListTransformSpec listSpec = new ListTransformSpec(transformId, null);
        listSpec.addSpec(new ReferenceTransformSpec("1"));

        dao.saveTransformSpec(stackId, listSpec);

        final TransformSpec updatedSpec = dao.getTransformSpec(stackId, transformId);

        assertNotNull(updatedSpec, "null transformSpec retrieved after update");
        assertFalse(updatedSpec.isFullyResolved(), "transformSpec should not be resolved after update");
    }

    @Test
    public void testUpdateZForSection() {

        final String sectionId = "mis-ordered-section";
        final Double zBeforeUpdate = dao.getZForSection(stackId, sectionId);

        assertEquals(3903.0, zBeforeUpdate, 0.1, "incorrect z before update");

        final double updatedZ = 999.0;
        dao.updateZForSection(stackId, "mis-ordered-section", updatedZ);

        final Double zAfterUpdate = dao.getZForSection(stackId, sectionId);

        assertEquals(updatedZ, zAfterUpdate, 0.1, "incorrect z before update");
    }

    @Test
    public void testUpdateZForTiles() {
        final String tileIdA = "134";
        final String tileIdB = "135";

        final List<String> tileIds = Arrays.asList(tileIdA, tileIdB);

        final Double zBeforeUpdateA = dao.getTileSpec(stackId, tileIdA, false).getZ();
        final Double zBeforeUpdateB = dao.getTileSpec(stackId, tileIdB, false).getZ();

        final Double updatedZ = 999.0;

        assertNotSame(updatedZ, zBeforeUpdateA,
                      "z for tile '" + tileIdA + "' should differ from update value");
        assertNotSame(updatedZ, zBeforeUpdateB,
                      "z for tile '" + tileIdB + "' should differ from update value");

        dao.updateZForTiles(stackId, updatedZ, tileIds);

        final Double zAfterUpdateA = dao.getTileSpec(stackId, tileIdA, false).getZ();
        final Double zAfterUpdateB = dao.getTileSpec(stackId, tileIdB, false).getZ();

        assertEquals(updatedZ, zAfterUpdateA, "z not updated for tile '" + tileIdA + "'");
        assertEquals(updatedZ, zAfterUpdateB, "z not updated for tile '" + tileIdB + "'");
    }

    public static void validateStackMetaData(final String context,
                                             final StackMetaData.StackState expectedState,
                                             final Integer expectedVersionNumber,
                                             final StackVersion expectedVersion,
                                             final StackMetaData actualMetaData) {

        assertNotNull(actualMetaData, "null meta data retrieved" + context);
        assertEquals(expectedState, actualMetaData.getState(),
                     "invalid state" + context);
        assertNotNull(actualMetaData.getLastModifiedTimestamp(),
                      "null modified date" + context);
        assertEquals(expectedVersionNumber, actualMetaData.getCurrentVersionNumber(),
                     "invalid version number" + context);

        final StackVersion actualVersion = actualMetaData.getCurrentVersion();
        assertNotNull(actualVersion, "null version for " + context);
        assertEquals(expectedVersion.getCreateTimestamp(), actualVersion.getCreateTimestamp(),
                     "invalid createTimestamp" + context);
        assertEquals(expectedVersion.getVersionNotes(), actualVersion.getVersionNotes(),
                     "invalid versionNotes" + context);
        assertEquals(expectedVersion.getCycleNumber(), actualVersion.getCycleNumber(),
                     "invalid cycleNumber" + context);
        assertEquals(expectedVersion.getCycleStepNumber(), actualVersion.getCycleStepNumber(),
                     "invalid cycleStepNumber" + context);
        assertEquals(expectedVersion.getStackResolutionX(), actualVersion.getStackResolutionX(),
                     "invalid stackResolutionX" + context);
        assertEquals(expectedVersion.getStackResolutionY(), actualVersion.getStackResolutionY(),
                     "invalid stackResolutionY" + context);
        assertEquals(expectedVersion.getStackResolutionZ(), actualVersion.getStackResolutionZ(),
                     "invalid stackResolutionZ" + context);
        assertEquals(expectedVersion.getMipmapPathBuilder(), actualVersion.getMipmapPathBuilder(),
                     "invalid mipmapPathBuilder" + context);
    }

}
