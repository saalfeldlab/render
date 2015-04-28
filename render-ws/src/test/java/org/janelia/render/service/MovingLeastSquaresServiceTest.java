package org.janelia.render.service;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.MovingLeastSquaresDerivationData;
import org.janelia.render.service.model.stack.StackId;
import org.janelia.test.EmbeddedMongoDb;
import org.jboss.resteasy.specimpl.UriInfoImpl;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the {@link MovingLeastSquaresService} class.
 *
 * @author Eric Trautman
 */
public class MovingLeastSquaresServiceTest {

    private static StackId alignStackId;
    private static StackId montageStackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDao dao;
    private static MovingLeastSquaresService service;

    @BeforeClass
    public static void before() throws Exception {
        alignStackId = new StackId("flyTEM", "fly863", "align");
        montageStackId = new StackId(alignStackId.getOwner(), alignStackId.getProject(), "montage");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());
        service = new MovingLeastSquaresService(dao);

        embeddedMongoDb.importCollection(alignStackId.getTileCollectionName(),
                                         new File("src/test/resources/mongodb/fly863_align__tile.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(alignStackId.getTransformCollectionName(),
                                         new File("src/test/resources/mongodb/fly863_acquire__transform.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(montageStackId.getTileCollectionName(),
                                         new File("src/test/resources/mongodb/fly863_montage__tile.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(montageStackId.getTransformCollectionName(),
                                         new File("src/test/resources/mongodb/fly863_acquire__transform.json"),
                                         true,
                                         false,
                                         true);
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testMovingLeastSquaresMethods() throws Exception {

        final TransformSpec mlsSpec = service.getMovingLeastSquaresTransform(alignStackId.getOwner(),
                                                                             alignStackId.getProject(),
                                                                             alignStackId.getStack(),
                                                                             Z,
                                                                             montageStackId.getStack(),
                                                                             null);

        Assert.assertNotNull("null mls spec returned", mlsSpec);

        final List<TileBounds> alignTileBoundsList = dao.getTileBounds(alignStackId, Z);

        Assert.assertNotNull("null align stack tile bounds list returned", alignTileBoundsList);

        Assert.assertEquals("invalid number of align stack tile bounds returned", 1, alignTileBoundsList.size());

        final TileBounds alignTileBounds = alignTileBoundsList.get(0);

        final UriInfo uriInfo = new UriInfoImpl(new URI("http://test/movingLeastSquaresTiles"),
                                                new URI("http://test"),
                                                "/movingLeastSquaresTiles",
                                                "",
                                                new ArrayList<PathSegment>());

        final StackId mlsStackId = new StackId(montageStackId.getOwner(), montageStackId.getProject(), "mls");
        service.saveMovingLeastSquaresTilesForLayer(mlsStackId.getOwner(),
                                                    mlsStackId.getProject(),
                                                    mlsStackId.getStack(),
                                                    Z,
                                                    uriInfo,
                                                    new MovingLeastSquaresDerivationData(alignStackId.getStack(),
                                                                                         montageStackId.getStack(),
                                                                                         null));

        final TransformSpec storedMlsSpec = dao.getTransformSpec(mlsStackId, mlsSpec.getId());

        Assert.assertNotNull("null mls spec returned for id " + mlsSpec.getId(), storedMlsSpec);

        assertTransformsAreEqual("stored mls spec does not match derived spec", mlsSpec, storedMlsSpec);

        final List<TileBounds> mlsTileBoundsList = dao.getTileBounds(mlsStackId, Z);

        Assert.assertNotNull("null mls stack tile bounds list returned", mlsTileBoundsList);

        Assert.assertEquals("invalid number of mls stack tile bounds returned", 1, mlsTileBoundsList.size());

        final TileBounds mlsTileBounds = mlsTileBoundsList.get(0);

        Assert.assertEquals("invalid mls tile id", alignTileBounds.getTileId(), mlsTileBounds.getTileId());

        Assert.assertEquals("invalid mls tile min X", 99222.0, mlsTileBounds.getMinX(), ACCEPTABLE_DELTA);
    }

    private void assertTransformsAreEqual(String message,
                                          TransformSpec expected,
                                          TransformSpec actual) {
        final String expectedJson = JsonUtils.GSON.toJson(expected);
        final String actualJson = JsonUtils.GSON.toJson(actual);
        Assert.assertEquals(message, expectedJson, actualJson);
    }

    private static final Double Z = 2337.0;
    private static final double ACCEPTABLE_DELTA = 0.1;
}
