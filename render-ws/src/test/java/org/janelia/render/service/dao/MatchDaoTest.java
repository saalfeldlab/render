package org.janelia.render.service.dao;

import com.mongodb.client.MongoCollection;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.alignment.match.MatchTrial;
import org.janelia.alignment.match.Matches;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests {@link MatchDao} methods that change persisted data.
 * The embedded database is rebuilt for each test, so these tests take longer to run.
 *
 * @author Eric Trautman
 */
public class MatchDaoTest {

    private MatchDao dao;
    private MatchCollectionId collectionId;
    private String groupId;

    @BeforeEach
    public void setUp() throws Exception {

        MatchDaoReadOnlyTest.before();

        this.dao = MatchDaoReadOnlyTest.getDao();
        this.collectionId = MatchDaoReadOnlyTest.getCollectionId();
        this.groupId = MatchDaoReadOnlyTest.getGroupId();
    }

    @AfterEach
    public void after() {
        MatchDaoReadOnlyTest.after();
    }

    @Test
    public void testRemoveMatchesInvolvingObject() throws Exception {

        // "pGroupId": "section0", "pId": "tile0.1", "qGroupId": "section1", "qId": "tile1.1",
        final String sourceId = "tile1.1";

        dao.removeMatchesInvolvingObject(collectionId, groupId, sourceId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesInvolvingObject(collectionId, null, groupId, sourceId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(0, canvasMatchesList.size(),
                     "invalid number of matches returned");
    }

    @Test
    public void testRemoveMatchesOutsideGroup() throws Exception {

        dao.removeMatchesOutsideGroup(collectionId, groupId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, true, outputStream);

        List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(0, canvasMatchesList.size(),
                     "after removal, invalid number of matches outside layer returned");

        outputStream.reset();

        dao.writeMatchesWithinGroup(collectionId, null, groupId, true, outputStream);

        canvasMatchesList = getListFromStream(outputStream);

        assertEquals(2, canvasMatchesList.size(),
                     "after removal, invalid number of matches within layer returned");
    }

    @Test
    public void testRemoveMatchesWithPGroup() throws Exception {

        dao.removeMatchesWithPGroup(collectionId, groupId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithPGroup(collectionId, null, groupId, true, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        assertEquals(0, canvasMatchesList.size(),
                     "after removal, invalid number of matches with pGroup returned");

        outputStream.reset();
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

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, false, outputStream);

        canvasMatchesList = getListFromStream(outputStream);

        assertEquals(5, canvasMatchesList.size(),
                     "invalid number of matches returned, matches=" + canvasMatchesList);

        CanvasMatches lastSavedMatchPair = null;
        int savePCount = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            if (pId.equals(canvasMatches.getpId())) {
                savePCount++;
                lastSavedMatchPair = canvasMatches;
            }
        }

        assertEquals(3, savePCount, "invalid number of matches saved");

        if (lastSavedMatchPair != null) {
            final Integer wLength = lastSavedMatchPair.getMatches().getWs().length;
            assertEquals(wLength, lastSavedMatchPair.getMatchCount(),
                         "invalid match count for last pair");
        }
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

        dao.writeMatchesOutsideGroup(collectionId, null, updateGroupA, false, outputStream);

        final List<CanvasMatches> retrievedList = getListFromStream(outputStream);

        assertEquals(1, retrievedList.size(),
                     "invalid number of matches returned, matches=" + retrievedList);

        for (final CanvasMatches canvasMatches : retrievedList) {
            final Matches matches = canvasMatches.getMatches();
            final double[] ws = matches.getWs();
            assertEquals(8.0, ws[0], 0.01, "weight not updated");
        }
    }

    @Test
    public void testRemoveMatches() {

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
        assertEquals(new Long(12), collectionMetaData.getPairCount(),
                     "invalid pair count before deletions");

        dao.removeMatchesBetweenTiles(deletionCollectionId, "0", tileA, "0", tileB);

        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        assertEquals(new Long(11), collectionMetaData.getPairCount(),
                     "invalid pair count after removing one tile pair");

        dao.removeMatchesBetweenGroups(deletionCollectionId, "0", "7");
        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        assertEquals(new Long(10), collectionMetaData.getPairCount(),
                     "invalid pair count after removing pairs between groups 0 and 7");

        dao.removeMatchesOutsideGroup(deletionCollectionId, "0");
        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        assertEquals(new Long(8), collectionMetaData.getPairCount(),
                     "invalid pair count after removing pairs outside group 0");

        dao.removeAllMatches(deletionCollectionId);
        collectionMetaData = getCollectionMetaData(deletionCollectionId);
        assertNull(collectionMetaData,
                   deletionCollectionId + " not removed");
    }

    @Test
    public void testRenameMatchCollection() {

        final MatchCollectionId toMatchCollectionId = new MatchCollectionId(collectionId.getOwner(),
                                                                            "new_and_improved");
        dao.renameMatchCollection(collectionId, toMatchCollectionId);

        boolean foundFromCollection = false;
        boolean foundToCollection = false;
        for (final MatchCollectionMetaData metaData : dao.getMatchCollectionMetaData()) {
            foundFromCollection = (! foundFromCollection) && collectionId.equals(metaData.getCollectionId());
            foundToCollection = (! foundToCollection) && toMatchCollectionId.equals(metaData.getCollectionId());
        }

        assertTrue(foundToCollection, "renamed collection " + toMatchCollectionId + " NOT found");
        assertFalse(foundFromCollection, "original collection " + collectionId + " still exists");
    }

    @Test
    public void testMatchTrial() {

        final String json = """
                        {
                          "parameters" : {
                            "featureAndMatchParameters" : {
                              "siftFeatureParameters" : {
                                "fdSize" : 4,
                                "minScale" : 0.5,
                                "maxScale" : 1.0,
                                "steps" : 3
                              },
                              "matchDerivationParameters" : {
                                "matchRod" : 0.95,
                                "matchModelType" : "AFFINE",
                                "matchIterations" : 1000,
                                "matchMaxEpsilon" : 5.0,
                                "matchMinInlierRatio" : 0.0,
                                "matchMinNumInliers" : 6,
                                "matchMaxTrust" : 30.0,
                                "matchFilter" : "AGGREGATED_CONSENSUS_SETS"
                              }
                            },
                            "pRenderParametersUrl" : "http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/spc_mm2_sample_rough_test_1_tier_3/stack/0016x0017_000118/tile/z_1015.0_box_5632_6656_1024_1024_0.500000/render-parameters?excludeMask=true&normalizeForMatching=true&filter=true&fillWithNoise=true",
                            "qRenderParametersUrl" : "http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/spc_mm2_sample_rough_test_1_tier_3/stack/0016x0017_000118/tile/z_1016.0_box_5632_6656_1024_1024_0.500000/render-parameters?excludeMask=true&normalizeForMatching=true&filter=true&fillWithNoise=true"
                          },
                          "matches" : [ ],
                          "stats" : {
                            "pFeatureCount" : 996,
                            "pFeatureDerivationMilliseconds" : 1415,
                            "qFeatureCount" : 1133,
                            "qFeatureDerivationMilliseconds" : 1279,
                            "consensusSetSizes" : [ 0 ],
                            "matchDerivationMilliseconds" : 324
                          }
                        }""";

        final MatchTrial matchTrial = MatchTrial.fromJson(json);

        final MatchTrial insertedTrial = dao.insertMatchTrial(matchTrial);

        final String trialId = insertedTrial.getId();
        assertNotNull(trialId, "trialId not set");

        assertEquals(matchTrial.getParameters().getpRenderParametersUrl(),
                     insertedTrial.getParameters().getpRenderParametersUrl(),
                     "invalid pRenderParametersUrl inserted");

        final MatchTrial retrievedTrial = dao.getMatchTrial(trialId);
        assertNotNull(retrievedTrial, "trial not saved");

        assertEquals(matchTrial.getParameters().getqRenderParametersUrl(),
                     retrievedTrial.getParameters().getqRenderParametersUrl(),
                     "invalid qRenderParametersUrl inserted");

        dao.removeMatchTrial(trialId);
        try {
            final MatchTrial removedTrial = dao.getMatchTrial(trialId);
            fail("trial that should have been removed was found: " + removedTrial);
        } catch (final ObjectNotFoundException e) {
            assertTrue(e.getMessage().contains(trialId),
                       "trial id missing from exception message: " + e.getMessage());
        }

        dao.removeMatchTrial(trialId);
        assertTrue(true, "removal of non-existent trial is ok");
    }

    @Test
    public void testUpdateMatchCountsForPGroup() {

        final String pGroupId = "section1";

        dao.updateMatchCountsForPGroup(collectionId, pGroupId);

        final MongoCollection<Document> matchCollection = dao.getExistingCollection(collectionId);
        final Document query = new Document("pGroupId", pGroupId);
        final List<CanvasMatches> updatedMatchList = dao.getMatches(matchCollection, query, false);

        assertEquals(3, updatedMatchList.size(),
                     "invalid number of matches returned, matches=" + updatedMatchList);

        for (final CanvasMatches canvasMatches : updatedMatchList) {
            final Matches matches = canvasMatches.getMatches();
            final Integer expectedMatchCount = matches.getWs().length;
            assertEquals(expectedMatchCount, canvasMatches.getMatchCount(),
                         "match counts not updated");
        }

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

}
