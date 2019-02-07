package org.janelia.render.service.dao;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests {@link MatchDao} methods or error cases that won't change stored data.
 * This allows the embedded database to be setup once for all of the tests and to be safely shared.
 * Avoiding setting up the database for each test reduces the amount of time each test takes.
 *
 * @author Eric Trautman
 */
public class MatchDaoReadOnlyTest {

    private static final MatchCollectionId collectionId = new MatchCollectionId("testOwner", "testCollection");
    private static final String groupId = "section1";

    private static List<MatchCollectionId> collectionIdBList;
    private static List<MatchCollectionId> collectionIdBandCAndBList;
    private static EmbeddedMongoDb embeddedMongoDb;

    private static MatchDao dao;

    @BeforeClass
    public static void before() throws Exception {
        final MatchCollectionId collectionIdB = new MatchCollectionId("testOwner", "testCollectionB");
        final MatchCollectionId collectionIdC = new MatchCollectionId("testOwner", "testCollectionC");

        collectionIdBList = Collections.singletonList(collectionIdB);

        collectionIdBandCAndBList = new ArrayList<>();
        Collections.addAll(collectionIdBandCAndBList, collectionIdB, collectionIdC, collectionIdB);

        embeddedMongoDb = new EmbeddedMongoDb(MatchDao.MATCH_DB_NAME);
        dao = new MatchDao(embeddedMongoDb.getMongoClient());

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
        embeddedMongoDb.importCollection(MatchDao.MATCH_TRIAL_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/matchTrial.json"),
                                         true,
                                         false,
                                         true);

    }

    @AfterClass
    public static void after() {
        embeddedMongoDb.stop();
    }

    static MatchDao getDao() {
        return dao;
    }

    static MatchCollectionId getCollectionId() {
        return collectionId;
    }

    static String getGroupId() {
        return groupId;
    }

    @Test
    public void testGetMatchCollectionMetaData() {

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
                Assert.assertEquals("invalid number of pairs", new Long(11), metaData.getPairCount());
            }
        }
        Assert.assertTrue("missing first collection", foundFirstCollection);
    }

    @Test
    public void testGetMultiConsensusPGroupIds() {

        final List<String> pGroupList = dao.getMultiConsensusPGroupIds(collectionId);

        Assert.assertEquals("invalid number of p group ids returned",
                            2, pGroupList.size());

        if (! pGroupList.contains("section10")) {
            Assert.fail("list missing section10, values are: " + pGroupList);
        }

        if (! pGroupList.contains("section13")) {
            Assert.fail("list missing section13, values are: " + pGroupList);
        }
    }

    @Test
    public void testGetMultiConsensusGroupIds() {

        final Set<String> groupList = dao.getMultiConsensusGroupIds(collectionId);

        Assert.assertEquals("invalid number of p group ids returned",
                            5, groupList.size());

        for (int i = 0; i < 5; i++) {
            final String groupId = "section1" + i;
            if (! groupList.contains(groupId)) {
                Assert.fail("list missing " + groupId + ", values are: " + groupList);
            }
        }
    }

    @Test
    public void testWriteMatchesWithPGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithPGroup(collectionId, null, groupId, false, outputStream);

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

        dao.writeMatchesWithinGroup(collectionId, null, groupId, false, outputStream);

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

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, false, outputStream);

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
        dao.writeMatchesBetweenGroups(collectionId, null, groupId, targetGroupId, false, outputStream);

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
    public void testWriteMatchesInvolvingObjectAndGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        // "pGroupId": "section0", "pId": "tile0.1", "qGroupId": "section1", "qId": "tile1.1",
        final String sourceId = "tile1.1";
        final String qGroupId = "section1";

        dao.writeMatchesBetweenObjectAndGroup(collectionId, null, groupId, sourceId,qGroupId, false, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            1, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertTrue("groupId '" + groupId + "' not found in " + canvasMatches,
                              groupId.equals(canvasMatches.getpGroupId()) ||
                              groupId.equals(canvasMatches.getqGroupId()) );
            Assert.assertTrue("id '" + sourceId + "' not found in " + canvasMatches,
                              sourceId.equals(canvasMatches.getpId()) ||
                              sourceId.equals(canvasMatches.getqId()) );
            Assert.assertTrue("qGroupId '" + qGroupId + "' not found in " + canvasMatches,
                              qGroupId.equals(canvasMatches.getpGroupId()) ||
                              qGroupId.equals(canvasMatches.getqGroupId()) );
        }
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

        dao.writeMatchesWithinGroup(collectionId, mergeCollectionIdList, groupId, false, outputStream);

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
