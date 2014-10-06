package org.janelia.render.service.dao;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * Tests the {@link org.janelia.render.service.dao.RenderParametersDao} class.
 *
 * @author Eric Trautman
 */
public class RenderParametersDaoTest {

    private static String owner;
    private static String project;
    private static String stack;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderParametersDao dao;

    @BeforeClass
    public static void before() throws Exception {
        owner = "flyTEM";
        project = "test";
        stack = "elastic";
        embeddedMongoDb = new EmbeddedMongoDb(RenderParametersDao.getDatabaseName(owner, project, stack));
        dao = new RenderParametersDao(embeddedMongoDb.getMongoClient());
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetParameters() throws Exception {

        embeddedMongoDb.importCollection(RenderParametersDao.TILE_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/elastic-3903.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(RenderParametersDao.TRANSFORM_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/elastic-transform.json"),
                                         true,
                                         false,
                                         true);

        final Double x = 1000.0;
        final Double y = 3000.0;
        final Double z = 3903.0;
        final Integer width = 5000;
        final Integer height = 2000;
        final Integer zoomLevel = 1;

        final RenderParameters parameters = dao.getParameters(owner, project, stack, x, y, z, width, height, zoomLevel);

        Assert.assertNotNull("null parameters retrieved", parameters);
        Assert.assertEquals("invalid width parsed", width.intValue(), parameters.getWidth());

        // validate that dao parameters can be re-serialized
        try {
            final String json = parameters.toJson();
            Assert.assertNotNull("null json string produced for parameters", json);
        } catch (Exception e) {
            LOG.error("failed to serialize json for " + parameters, e);
            Assert.fail("retrieved parameters cannot be re-serialized to json");
        }

        parameters.initializeDerivedValues();
        final List<TileSpec> tileSpecs = parameters.getTileSpecs();
        Assert.assertNotNull("null tile specs value after init", tileSpecs);
        Assert.assertEquals("invalid number of tiles after init", 6, tileSpecs.size());

        ListTransformSpec transforms;
        for (TileSpec tileSpec : tileSpecs) {
            transforms = tileSpec.getTransforms();
            Assert.assertTrue("tileSpec " + tileSpec.getTileId() + " is not fully resolved",
                              transforms.isFullyResolved());
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDaoTest.class);
}
