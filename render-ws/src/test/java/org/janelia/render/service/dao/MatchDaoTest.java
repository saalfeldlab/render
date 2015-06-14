package org.janelia.render.service.dao;

import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasMatches;
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

    private static String collectionName;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static MatchDao dao;

    private final String sectionId = "section1";

    @BeforeClass
    public static void before() throws Exception {
        collectionName = "test";
        embeddedMongoDb = new EmbeddedMongoDb(MatchDao.MATCH_DB_NAME);
        dao = new MatchDao(embeddedMongoDb.getMongoClient());
    }

    @Before
    public void setUp() throws Exception {
        embeddedMongoDb.importCollection(collectionName,
                                         new File("src/test/resources/mongodb/match.json"),
                                         true,
                                         false,
                                         true);
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testWriteMatchesWithinLayer() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesWithinLayer(collectionName, sectionId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            2, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {
//            System.out.println(canvasMatches.toTabSeparatedFormat());
            Assert.assertEquals("invalid source sectionId: " + canvasMatches, sectionId, canvasMatches.getpSectionId());
            Assert.assertEquals("invalid target sectionId: " + canvasMatches, sectionId, canvasMatches.getqSectionId());
        }
    }

    @Test
    public void testWriteMatchesOutsideLayer() throws Exception {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideLayer(collectionName, sectionId, outputStream);

        final List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("invalid number of matches returned",
                            2, canvasMatchesList.size());

        for (final CanvasMatches canvasMatches : canvasMatchesList) {

            System.out.println(canvasMatches.toTabSeparatedFormat());

            Assert.assertNotSame("source and target matches have same sectionId: " + canvasMatches,
                                 canvasMatches.getpSectionId(), canvasMatches.getqSectionId());
        }
    }

    @Test
    public void testRemoveMatchesOutsideLayer() throws Exception {

        dao.removeMatchesOutsideLayer(collectionName, sectionId);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideLayer(collectionName, sectionId, outputStream);

        List<CanvasMatches> canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches outside layer returned",
                            0, canvasMatchesList.size());

        outputStream.reset();

        dao.writeMatchesWithinLayer(collectionName, sectionId, outputStream);

        canvasMatchesList = getListFromStream(outputStream);

        Assert.assertEquals("after removal, invalid number of matches within layer returned",
                            2, canvasMatchesList.size());
    }

    @Test
    public void testSaveMatches() throws Exception {

        final String pId = "save.p";

        List<CanvasMatches> canvasMatchesList = new ArrayList<>();
        for (int i = 1; i < 4; i++) {
            canvasMatchesList.add(new CanvasMatches(sectionId,
                                                    pId,
                                                    sectionId + i,
                                                    "save.q",
                                                    new Matches(new double[][]{{1, 2, 3}, {4, 5, 6},},
                                                                new double[][]{{11, 12, 13}, {14, 15, 16}},
                                                                new double[]{7, 8, 9})));
        }

        dao.saveMatches(collectionName, canvasMatchesList);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(1024);

        dao.writeMatchesOutsideLayer(collectionName, sectionId, outputStream);

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

    private List<CanvasMatches> getListFromStream(final ByteArrayOutputStream outputStream) {
        final String json = outputStream.toString();
        final Type typeOfT = new TypeToken<List<CanvasMatches>>(){}.getType();
        return JsonUtils.GSON.fromJson(json, typeOfT);
    }

}
