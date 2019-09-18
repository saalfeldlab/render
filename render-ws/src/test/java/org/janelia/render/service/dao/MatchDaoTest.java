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
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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

    @Before
    public void setUp() throws Exception {

        MatchDaoReadOnlyTest.before();

        this.dao = MatchDaoReadOnlyTest.getDao();
        this.collectionId = MatchDaoReadOnlyTest.getCollectionId();
        this.groupId = MatchDaoReadOnlyTest.getGroupId();
    }

    @After
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

        Assert.assertEquals("invalid number of matches returned",
                            0, canvasMatchesList.size());
    }

    @Test
    public void testRemoveMatchesOutsideGroup() throws Exception {

        dao.removeMatchesOutsideGroup(collectionId, groupId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideGroup(collectionId, null, groupId, true, outputStream);

        List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches outside layer returned",
                            0, canvasMatchesList.size());

        outputStream.reset();

        dao.writeMatchesWithinGroup(collectionId, null, groupId, true, outputStream);

        canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches within layer returned",
                            2, canvasMatchesList.size());
    }

    @Test
    public void testRemoveMatchesWithPGroup() throws Exception {

        dao.removeMatchesWithPGroup(collectionId, groupId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithPGroup(collectionId, null, groupId, true, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches with pGroup returned",
                            0, canvasMatchesList.size());

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

        Assert.assertEquals("invalid number of matches returned, matches=" + canvasMatchesList,
                            5, canvasMatchesList.size());

        CanvasMatches lastSavedMatchPair = null;
        int savePCount = 0;
        for (final CanvasMatches canvasMatches : canvasMatchesList) {
            if (pId.equals(canvasMatches.getpId())) {
                savePCount++;
                lastSavedMatchPair = canvasMatches;
            }
        }

        Assert.assertEquals("invalid number of matches saved", 3, savePCount);

        if (lastSavedMatchPair != null) {
            final Integer wLength = lastSavedMatchPair.getMatches().getWs().length;
            Assert.assertEquals("invalid match count for last pair",
                                wLength, lastSavedMatchPair.getMatchCount());
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

        Assert.assertEquals("invalid number of matches returned, matches=" + retrievedList,
                            1, retrievedList.size());

        for (final CanvasMatches canvasMatches : retrievedList) {
            final Matches matches = canvasMatches.getMatches();
            final double[] ws = matches.getWs();
            Assert.assertEquals("weight not updated", 8.0, ws[0], 0.01);
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

        Assert.assertTrue("renamed collection " + toMatchCollectionId + " NOT found", foundToCollection);
        Assert.assertFalse("original collection " + collectionId + " still exists", foundFromCollection);
    }

    @Test
    public void testMatchTrial() {

        final String json =
                "{\n" +
                "  \"parameters\" : {\n" +
                "    \"featureAndMatchParameters\" : {\n" +
                "      \"siftFeatureParameters\" : {\n" +
                "        \"fdSize\" : 4,\n" +
                "        \"minScale\" : 0.5,\n" +
                "        \"maxScale\" : 1.0,\n" +
                "        \"steps\" : 3\n" +
                "      },\n" +
                "      \"matchDerivationParameters\" : {\n" +
                "        \"matchRod\" : 0.95,\n" +
                "        \"matchModelType\" : \"AFFINE\",\n" +
                "        \"matchIterations\" : 1000,\n" +
                "        \"matchMaxEpsilon\" : 5.0,\n" +
                "        \"matchMinInlierRatio\" : 0.0,\n" +
                "        \"matchMinNumInliers\" : 6,\n" +
                "        \"matchMaxTrust\" : 30.0,\n" +
                "        \"matchFilter\" : \"AGGREGATED_CONSENSUS_SETS\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"pRenderParametersUrl\" : \"http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/spc_mm2_sample_rough_test_1_tier_3/stack/0016x0017_000118/tile/z_1015.0_box_5632_6656_1024_1024_0.500000/render-parameters?excludeMask=true&normalizeForMatching=true&filter=true&fillWithNoise=true\",\n" +
                "    \"qRenderParametersUrl\" : \"http://renderer-dev:8080/render-ws/v1/owner/flyTEM/project/spc_mm2_sample_rough_test_1_tier_3/stack/0016x0017_000118/tile/z_1016.0_box_5632_6656_1024_1024_0.500000/render-parameters?excludeMask=true&normalizeForMatching=true&filter=true&fillWithNoise=true\"\n" +
                "  },\n" +
                "  \"matches\" : [ ],\n" +
                "  \"stats\" : {\n" +
                "    \"pFeatureCount\" : 996,\n" +
                "    \"pFeatureDerivationMilliseconds\" : 1415,\n" +
                "    \"qFeatureCount\" : 1133,\n" +
                "    \"qFeatureDerivationMilliseconds\" : 1279,\n" +
                "    \"consensusSetSizes\" : [ 0 ],\n" +
                "    \"matchDerivationMilliseconds\" : 324\n" +
                "  }\n" +
                "}";

        final MatchTrial matchTrial = MatchTrial.fromJson(json);

        final MatchTrial insertedTrial = dao.insertMatchTrial(matchTrial);

        final String trialId = insertedTrial.getId();
        Assert.assertNotNull("trialId not set", trialId);

        Assert.assertEquals("invalid pRenderParametersUrl inserted",
                            matchTrial.getParameters().getpRenderParametersUrl(), insertedTrial.getParameters().getpRenderParametersUrl());

        final MatchTrial retrievedTrial = dao.getMatchTrial(trialId);
        Assert.assertNotNull("trial not saved", retrievedTrial);

        Assert.assertEquals("invalid qRenderParametersUrl inserted",
                            matchTrial.getParameters().getqRenderParametersUrl(), retrievedTrial.getParameters().getqRenderParametersUrl());

    }

    @Test
    public void testUpdateMatchCountsForPGroup() {

        final String pGroupId = "section1";

        dao.updateMatchCountsForPGroup(collectionId, pGroupId);

        final MongoCollection<Document> matchCollection = dao.getExistingCollection(collectionId);
        final Document query = new Document("pGroupId", pGroupId);
        final List<CanvasMatches> updatedMatchList = dao.getMatches(matchCollection, query, false);

        Assert.assertEquals("invalid number of matches returned, matches=" + updatedMatchList,
                            3, updatedMatchList.size());

        for (final CanvasMatches canvasMatches : updatedMatchList) {
            final Matches matches = canvasMatches.getMatches();
            final Integer expectedMatchCount = matches.getWs().length;
            Assert.assertEquals("match counts not updated",
                                expectedMatchCount, canvasMatches.getMatchCount());
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
