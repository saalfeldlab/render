package org.janelia.render.service.dao;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.Matches;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the {@link MatchDao} class.
 *
 * @author Eric Trautman
 */
public class MatchDaoTest {

    private static MatchCollectionId collectionId;
    private static MatchCollectionId collectionIdB;
    private static MatchCollectionId collectionIdC;
    private static List<MatchCollectionId> collectionIdBList;
    private static List<MatchCollectionId> collectionIdBandCAndBList;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static MatchDao dao;

    private final String groupId = "section1";

    @BeforeClass
    public static void before() throws Exception {
        collectionId = new MatchCollectionId("testOwner", "testCollection");
        collectionIdB = new MatchCollectionId("testOwner", "testCollectionB");
        collectionIdC = new MatchCollectionId("testOwner", "testCollectionC");

        collectionIdBList = Collections.singletonList(collectionIdB);

        collectionIdBandCAndBList = new ArrayList<>();
        Collections.addAll(collectionIdBandCAndBList, collectionIdB, collectionIdC, collectionIdB);

        embeddedMongoDb = new EmbeddedMongoDb(MatchDao.MATCH_DB_NAME);
        dao = new MatchDao(embeddedMongoDb.getMongoClient());
    }

    @Before
    public void setUp() throws Exception {
        embeddedMongoDb.importCollection(collectionId.getDbCollectionName(),
                                         new File("src/test/resources/mongodb/match.json"),
                                         true,
                                         false,
                                         true);
        embeddedMongoDb.importCollection(collectionIdB.getDbCollectionName(),
                                         new File("src/test/resources/mongodb/match_b.json"),
                                         true,
                                         false,
                                         true);
        embeddedMongoDb.importCollection(collectionIdC.getDbCollectionName(),
                                         new File("src/test/resources/mongodb/match_c.json"),
                                         true,
                                         false,
                                         true);
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetMatchCollectionMetaData() throws Exception {

        final List<MatchCollectionMetaData> metaDataList = dao.getMatchCollectionMetaData();
        Assert.assertEquals("invalid number of match collections returned",
                            3, metaDataList.size());

        boolean foundFirstCollection = false;
        MatchCollectionId retrievedCollectionId;
        for (final MatchCollectionMetaData metaData : metaDataList) {
            retrievedCollectionId = metaData.getCollectionId();
            Assert.assertNotNull("null collection id", retrievedCollectionId);
            Assert.assertEquals("invalid owner", collectionId.getOwner(), retrievedCollectionId.getOwner());
            if (collectionId.getName().equals(retrievedCollectionId.getName())) {
                foundFirstCollection = true;
                Assert.assertEquals("invalid number of pairs", new Long(4), metaData.getPairCount());
            }
        }
        Assert.assertTrue("missing first collection", foundFirstCollection);
    }

    @Test
    public void testWriteMatchesWithPGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithPGroup(collectionId, null, groupId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            3, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertEquals("invalid source groupId: " + canvasMatches, groupId, canvasMatches.getpGroupId());
        }
    }

    @Test
    public void testWriteMatchesWithinGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithinGroup(collectionId, null, groupId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            2, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertEquals("invalid source groupId: " + canvasMatches, groupId, canvasMatches.getpGroupId());
            Assert.assertEquals("invalid target groupId: " + canvasMatches, groupId, canvasMatches.getqGroupId());
        }
    }

    @Test
    public void testWriteMergedMatchesWithinGroup() throws Exception {

        final Map<String, Integer> mergedFirstTileIdsToMatchCountMap = new HashMap<>();
        mergedFirstTileIdsToMatchCountMap.put("tile1.3", 6);

        validateWriteMergedMatchesWithinGroup("two collection merge",
                                              collectionIdBList,
                                              4,
                                              mergedFirstTileIdsToMatchCountMap);

        mergedFirstTileIdsToMatchCountMap.put("tile1.3", 9);
        mergedFirstTileIdsToMatchCountMap.put("tile1.1b", 6);

        validateWriteMergedMatchesWithinGroup("three collection merge",
                                              collectionIdBandCAndBList,
                                              5,
                                              mergedFirstTileIdsToMatchCountMap);

    }

    @Test
    public void testWriteMatchesOutsideGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            2, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertNotSame("source and target matches have same groupId: " + canvasMatches,
                                 canvasMatches.getpGroupId(), canvasMatches.getqGroupId());
        }
    }

    @Test
    public void testWriteMatchesBetweenGroups() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        final String targetGroupId = "section2";
        dao.writeMatchesBetweenGroups(collectionId, null, groupId, targetGroupId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            1, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertEquals("matches have invalid pGroupId: " + canvasMatches,
                                 groupId, canvasMatches.getpGroupId());
            Assert.assertEquals("matches have invalid qGroupId: " + canvasMatches,
                                targetGroupId, canvasMatches.getqGroupId());
        }
    }

    @Test
    public void testWriteMatchesBetweenObjects() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        // "pGroupId": "section0", "pId": "tile0.1", "qGroupId": "section1", "qId": "tile1.1",
        final String sourceId = "tile1.1";
        final String targetGroupId = "section0";
        final String targetId = "tile0.1";

        dao.writeMatchesBetweenObjects(collectionId, null, groupId, sourceId, targetGroupId, targetId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            1, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertEquals("matches have invalid pGroupId (should be normalized): " + canvasMatches,
                                targetGroupId, canvasMatches.getpGroupId());
            Assert.assertEquals("matches have invalid pId (should be normalized): " + canvasMatches,
                                targetId, canvasMatches.getpId());
            Assert.assertEquals("matches have invalid qGroupId (should be normalized): " + canvasMatches,
                                groupId, canvasMatches.getqGroupId());
            Assert.assertEquals("matches have invalid qId (should be normalized): " + canvasMatches,
                                sourceId, canvasMatches.getqId());
        }
    }

    @Test
    public void testWriteMatchesInvolvingObject() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        // "pGroupId": "section0", "pId": "tile0.1", "qGroupId": "section1", "qId": "tile1.1",
        final String sourceId = "tile1.1";

        dao.writeMatchesInvolvingObject(collectionId, null, groupId, sourceId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            3, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertTrue("groupId '" + groupId + "' not found in " + canvasMatches,
                              groupId.equals(canvasMatches.getpGroupId()) ||
                              groupId.equals(canvasMatches.getqGroupId()) );
            Assert.assertTrue("id '" + sourceId + "' not found in " + canvasMatches,
                              sourceId.equals(canvasMatches.getpId()) ||
                              sourceId.equals(canvasMatches.getqId()) );
        }
    }

    @Test
    public void testRemoveMatchesOutsideGroup() throws Exception {

        dao.removeMatchesOutsideGroup(collectionId, groupId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, outputStream);

        List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches outside layer returned",
                            0, canvasMatchesList.size());

        outputStream.reset();

        dao.writeMatchesWithinGroup(collectionId, null, groupId, outputStream);

        canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches within layer returned",
                            2, canvasMatchesList.size());
    }

    @Test
    public void testSaveMatches() throws Exception {

        final String pId = "save.p";

        List<CanvasMatches> canvasMatchesList = new ArrayList<>();
        for (int i = 1; i < 4; i++) {
            canvasMatchesList.add(new CanvasMatches(groupId,
                                                    pId,
                                                    groupId + i,
                                                    "save.q",
                                                    new Matches(new double[][]{{1, 2, 3}, {4, 5, 6},},
                                                                new double[][]{{11, 12, 13}, {14, 15, 16}},
                                                                new double[]{7, 8, 9})));
        }

        dao.saveMatches(collectionId, canvasMatchesList);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, outputStream);

        canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned, matches=" + canvasMatchesList,
                            5, canvasMatchesList.size());

        int savePCount = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            if (pId.equals(canvasMatches.getpId())) {
                savePCount++;
            }
        }

        Assert.assertEquals("invalid number of matches saved", 3, savePCount);
    }

    @Test
    public void testUpdateMatches() throws Exception {

        final String updateGroupA = "updateGroupA";
        final CanvasMatches insertMatches = new CanvasMatches(updateGroupA,
                                                              "tile.p",
                                                              "section.b",
                                                              "tile.q",
                                                              new Matches(new double[][]{{1}, {4},},
                                                                          new double[][]{{11}, {14}},
                                                                          new double[]{7}));

        final List<CanvasMatches> insertList = new ArrayList<>();
        insertList.add(insertMatches);

        dao.saveMatches(collectionId, insertList);

        final CanvasMatches updateMatches = new CanvasMatches(insertMatches.getpGroupId(),
                                                              insertMatches.getpId(),
                                                              insertMatches.getqGroupId(),
                                                              insertMatches.getqId(),
                                                              new Matches(new double[][]{{2}, {5},},
                                                                          new double[][]{{12}, {15}},
                                                                          new double[]{8}));
        final List<CanvasMatches> updateList = new ArrayList<>();
        updateList.add(updateMatches);

        dao.saveMatches(collectionId, updateList);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideGroup(collectionId, null, updateGroupA, outputStream);

        final List<CanvasMatches> retrievedList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned, matches=" + retrievedList,
                            1, retrievedList.size());

        for (final CanvasMatches canvasMatches : retrievedList) {
            final Matches matches = canvasMatches.getMatches();
            final double ws[] = matches.getWs();
            Assert.assertEquals("weight not updated", 8.0, ws[0], 0.01);
        }
    }

    @Test
    public void testRemoveMatches() throws Exception {

        final MatchCollectionId deletionCollectionId = new MatchCollectionId("testOwner", "deletionCollection");

        final String tileA = "tileA";
        final String tileB = "tileB";
        final List<CanvasMatches> insertList = new ArrayList<>();
        for (int pGroup = 0; pGroup < 3; pGroup++) {
            insertList.add(
                    new CanvasMatches(String.valueOf(pGroup), tileA,
                                      String.valueOf(pGroup), tileB,
                                      new Matches(new double[][]{{1}, {4},},
                                                  new double[][]{{11}, {14}},
                                                  new double[]{7})));
            for (int qGroup = 7; qGroup < 10; qGroup++) {
                insertList.add(
                        new CanvasMatches(String.valueOf(pGroup), tileA,
                                          String.valueOf(qGroup), tileB,
                                          new Matches(new double[][]{{1}, {4},},
                                                      new double[][]{{11}, {14}},
                                                      new double[]{7})));
            }
        }

        dao.saveMatches(deletionCollectionId, insertList);

        MatchCollectionMetaData collectionMetaData = getCollectionMetaData(deletionCollectionId);
        Assert.assertEquals("invalid pair count before deletions",
                            new Long(12), collectionMetaData.getPairCount());

        dao.removeMatchesBetweenTiles(deletionCollectionId, "0", tileA, "0", tileB);

        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        Assert.assertEquals("invalid pair count after removing one tile pair",
                            new Long(11), collectionMetaData.getPairCount());

        dao.removeMatchesBetweenGroups(deletionCollectionId, "0", "7");
        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        Assert.assertEquals("invalid pair count after removing pairs between groups 0 and 7",
                            new Long(10), collectionMetaData.getPairCount());

        dao.removeMatchesOutsideGroup(deletionCollectionId, "0");
        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        Assert.assertEquals("invalid pair count after removing pairs outside group 0",
                            new Long(8), collectionMetaData.getPairCount());

        dao.removeAllMatches(deletionCollectionId);
        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        Assert.assertNull(deletionCollectionId + " not removed",
                          collectionMetaData);
    }

    private MatchCollectionMetaData getCollectionMetaData(final MatchCollectionId collectionId) {
        MatchCollectionMetaData metaData = null;
        for (final MatchCollectionMetaData md : dao.getMatchCollectionMetaData()) {
            if (collectionId.equals(md.getCollectionId())) {
                metaData = md;
                break;
            }
        }
        return metaData;
    }

    private List<CanvasMatches> getListFromStream(final ByteArrayOutputStream outputStream) {
        final String json = outputStream.toString();
        return CanvasMatches.fromJsonArray(json);
    }

    private void validateWriteMergedMatchesWithinGroup(final String context,
                                                       final List<MatchCollectionId> mergeCollectionIdList,
                                                       final int expectedMatchCount,
                                                       final Map<String, Integer> mergedFirstTileIdsToMatchCountMap)
            throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithinGroup(collectionId, mergeCollectionIdList, groupId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals(context + " invalid number of matches returned",
                            expectedMatchCount, canvasMatchesList.size());

        int mergedTileCount = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            // System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertEquals(context + " invalid source groupId: " + canvasMatches,
                                groupId, canvasMatches.getpGroupId());
            Assert.assertEquals(context + " invalid target groupId: " + canvasMatches,
                                groupId, canvasMatches.getqGroupId());

            if (mergedFirstTileIdsToMatchCountMap.containsKey(canvasMatches.getpId())) {
                mergedTileCount++;
                Assert.assertEquals(context + " invalid number of matches for " + canvasMatches,
                                    mergedFirstTileIdsToMatchCountMap.get(canvasMatches.getpId()),
                                    new Integer(canvasMatches.size()));
            } else {
                Assert.assertEquals(context + " invalid number of matches for " + canvasMatches,
                                    3, canvasMatches.size());
            }
        }

        Assert.assertEquals(context + " invalid number of merged tile pairs",
                            mergedFirstTileIdsToMatchCountMap.size(), mergedTileCount);
    }

}
