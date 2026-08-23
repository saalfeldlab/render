package org.janelia.render.service.dao;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.ResolvedTileSpecsWithMatchPairs;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.janelia.render.service.dao.MatchDaoWithoutEmbeddedMongoTest.buildTileSpec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests {@link MatchDao} methods or error cases that won't change stored data.
 * This allows the embedded database to be setup once for all tests and to be safely shared.
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

    @BeforeAll
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

    @AfterAll
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
        assertEquals(3, metaDataList.size(),
                     "invalid number of match collections returned");

        boolean foundFirstCollection = false;
        MatchCollectionId retrievedCollectionId;
        for (final MatchCollectionMetaData metaData : metaDataList) {
            retrievedCollectionId = metaData.getCollectionId();
            assertNotNull(retrievedCollectionId, "null collection id");
            assertEquals(collectionId.getOwner(), retrievedCollectionId.getOwner(), "invalid owner");
            if (collectionId.getName().equals(retrievedCollectionId.getName())) {
                foundFirstCollection = true;
                assertEquals(Long.valueOf(11), metaData.getPairCount(), "invalid number of pairs");
            }
        }
        assertTrue(foundFirstCollection, "missing first collection");
    }

    @Test
    public void testGetMultiConsensusPGroupIds() {

        final List<String> pGroupList = dao.getMultiConsensusPGroupIds(collectionId);

        assertEquals(2, pGroupList.size(),
                     "invalid number of p group ids returned");

        if (! pGroupList.contains("section10")) {
            fail("list missing section10, values are: " + pGroupList);
        }

        if (! pGroupList.contains("section13")) {
            fail("list missing section13, values are: " + pGroupList);
        }
    }

    @Test
    public void testGetMultiConsensusGroupIds() {

        final Set<String> groupList = dao.getMultiConsensusGroupIds(collectionId);

        assertEquals(5, groupList.size(),
                     "invalid number of p group ids returned");

        for (int i = 0; i < 5; i++) {
            final String groupId = "section1" + i;
            if (! groupList.contains(groupId)) {
                fail("list missing " + groupId + ", values are: " + groupList);
            }
        }
    }

    @Test
    public void testWriteMatchesWithPGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithPGroup(collectionId, null, groupId, false, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(3, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertEquals(groupId, canvasMatches.getpGroupId(), "invalid source groupId: " + canvasMatches);
        }
    }

    @Test
    public void testWriteMatchesWithinGroup() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithinGroup(collectionId, null, groupId, false, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(2, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertEquals(groupId, canvasMatches.getpGroupId(), "invalid source groupId: " + canvasMatches);
            assertEquals(groupId, canvasMatches.getqGroupId(), "invalid target groupId: " + canvasMatches);
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

        assertEquals(2, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertNotSame(canvasMatches.getpGroupId(), canvasMatches.getqGroupId(),
                          "source and target matches have same groupId: " + canvasMatches);
        }
    }

    @Test
    public void testWriteMatchesBetweenGroups() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        final String targetGroupId = "section2";
        dao.writeMatchesBetweenGroups(collectionId, null, groupId, targetGroupId, false, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(1, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertEquals(groupId, canvasMatches.getpGroupId(),
                         "matches have invalid pGroupId: " + canvasMatches);
            assertEquals(targetGroupId, canvasMatches.getqGroupId(),
                         "matches have invalid qGroupId: " + canvasMatches);
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

        assertEquals(1, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertEquals(targetGroupId, canvasMatches.getpGroupId(),
                         "matches have invalid pGroupId (should be normalized): " + canvasMatches);
            assertEquals(targetId, canvasMatches.getpId(),
                         "matches have invalid pId (should be normalized): " + canvasMatches);
            assertEquals(groupId, canvasMatches.getqGroupId(),
                         "matches have invalid qGroupId (should be normalized): " + canvasMatches);
            assertEquals(sourceId, canvasMatches.getqId(),
                         "matches have invalid qId (should be normalized): " + canvasMatches);
        }
    }

    @Test
    public void testWriteMatchesInvolvingObject() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        // "pGroupId": "section0", "pId": "tile0.1", "qGroupId": "section1", "qId": "tile1.1",
        final String sourceId = "tile1.1";

        dao.writeMatchesInvolvingObject(collectionId, null, groupId, sourceId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(3, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertTrue(groupId.equals(canvasMatches.getpGroupId()) ||
                       groupId.equals(canvasMatches.getqGroupId()),
                       "groupId '" + groupId + "' not found in " + canvasMatches);
            assertTrue(sourceId.equals(canvasMatches.getpId()) ||
                       sourceId.equals(canvasMatches.getqId()),
                       "id '" + sourceId + "' not found in " + canvasMatches);
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

        assertEquals(1, canvasMatchesList.size(),
                     "invalid number of matches returned");

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            assertTrue(groupId.equals(canvasMatches.getpGroupId()) ||
                       groupId.equals(canvasMatches.getqGroupId()),
                       "groupId '" + groupId + "' not found in " + canvasMatches);
            assertTrue(sourceId.equals(canvasMatches.getpId()) ||
                       sourceId.equals(canvasMatches.getqId()),
                       "id '" + sourceId + "' not found in " + canvasMatches);
            assertTrue(qGroupId.equals(canvasMatches.getpGroupId()) ||
                       qGroupId.equals(canvasMatches.getqGroupId()),
                       "qGroupId '" + qGroupId + "' not found in " + canvasMatches);
        }
    }

    @Test
    public void testWriteMatchesAndTileSpecs() throws Exception {

        // add tile specs that have same ids as match.json test data (imported into collectionId)
        final ResolvedTileSpecCollection tileSpecs = new ResolvedTileSpecCollection();
        tileSpecs.addTileSpecToCollection(buildTileSpec("section1", "tile1.1"));
        tileSpecs.addTileSpecToCollection(buildTileSpec("section2", "tile2.1"));

        // tile1.1 has 3 pairs in match.json, one of those is with tile2.1 and
        // tile2.1 only has one pair (the one with tile1.1)

        // only 3 match pairs should be returned when all tiles are in same query
        testWriteMatchesAndTileSpecsWithTileBatchSize("single batch",
                                                      tileSpecs,
                                                      2,
                                                      3);

        // 4 match pairs should be returned when tiles are split between in same query
        testWriteMatchesAndTileSpecsWithTileBatchSize("split batches",
                                                      tileSpecs,
                                                      1,
                                                      4);
    }

    private static void testWriteMatchesAndTileSpecsWithTileBatchSize(final String context,
                                                                      final ResolvedTileSpecCollection tileSpecs,
                                                                      final int maxTilesPerQuery,
                                                                      final int expectedResultPairCount)
            throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesAndTileSpecs(collectionId, tileSpecs, outputStream, maxTilesPerQuery);

        final String json = outputStream.toString();
        final ResolvedTileSpecsWithMatchPairs tilesWithPairs =
                ResolvedTileSpecsWithMatchPairs.fromJson(new StringReader(json));

        final ResolvedTileSpecCollection resultTiles = tilesWithPairs.getResolvedTileSpecs();
        assertEquals(tileSpecs.getTileCount(), resultTiles.getTileCount(),
                     context + " tile counts do not match");

        final List<CanvasMatches> resultPairs = tilesWithPairs.getMatchPairs();
        assertEquals(expectedResultPairCount, resultPairs.size(),
                     context + " pair counts do not match");
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

        assertEquals(expectedMatchCount, canvasMatchesList.size(),
                     context + " invalid number of matches returned");

        int mergedTileCount = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            // System.out.println(canvasMatches.toTabSeparatedFormat());
            assertEquals(groupId, canvasMatches.getpGroupId(),
                         context + " invalid source groupId: " + canvasMatches);
            assertEquals(groupId, canvasMatches.getqGroupId(),
                         context + " invalid target groupId: " + canvasMatches);

            if (mergedFirstTileIdsToMatchCountMap.containsKey(canvasMatches.getpId())) {
                mergedTileCount++;
                assertEquals(mergedFirstTileIdsToMatchCountMap.get(canvasMatches.getpId()),
                             Integer.valueOf(canvasMatches.size()),
                             context + " invalid number of matches for " + canvasMatches);
            } else {
                assertEquals(3, canvasMatches.size(),
                             context + " invalid number of matches for " + canvasMatches);
            }
        }

        assertEquals(mergedFirstTileIdsToMatchCountMap.size(), mergedTileCount,
                     context + " invalid number of merged tile pairs");
    }

}
