package org.janelia.render.service.dao;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.ObjectNotFoundException;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
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
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetParameters() throws Exception {

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

    @Test
    public void testGetTileSpec() throws Exception {
        final Double z = 3903.0;
        final TileSpec tileSpec = dao.getTileSpec(owner, project, stack, z, "134");

        Assert.assertNotNull("null tileSpec retrieved", tileSpec);
        Assert.assertEquals("invalid z retrieved", String.valueOf(z), String.valueOf(tileSpec.getZ()));
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTileSpecWithBadZ() throws Exception {
        final Double z = 1234.5;
        dao.getTileSpec(owner, project, stack, z, "134");
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTileSpecWithBadId() throws Exception {
        final Double z = 3903.0;
        dao.getTileSpec(owner, project, stack, z, "missingId");
    }

    @Test
    public void testSaveTileSpec() throws Exception {
        final Double z = 123.0;
        final String tileId = "new-tile-1";
        final String temca = "0";
        final LayoutData layoutData = new LayoutData(temca, null, null, null);

        TileSpec tileSpec = new TileSpec();
        tileSpec.setZ(z);
        tileSpec.setTileId(tileId);
        tileSpec.setLayout(layoutData);

        dao.saveTileSpec(owner, project, stack, tileSpec);

        final TileSpec insertedTileSpec = dao.getTileSpec(owner, project, stack, z, tileId);

        Assert.assertNotNull("null tileSpec retrieved after insert", insertedTileSpec);
        final LayoutData insertedLayoutData = insertedTileSpec.getLayout();
        Assert.assertNotNull("null layout retrieved after insert", insertedLayoutData);
        Assert.assertEquals("invalid temca retrieved after insert", temca, insertedLayoutData.getTemca());
        Assert.assertFalse("tileSpec is has transforms after insert", tileSpec.hasTransforms());

        final String changedTemca = "1";
        final LayoutData changedLayoutData = new LayoutData(changedTemca, null, null, null);
        tileSpec.setLayout(changedLayoutData);
        final List<TransformSpec> list = new ArrayList<TransformSpec>();
        list.add(new ReferenceTransformSpec("1"));
        tileSpec.addTransformSpecs(list);

        dao.saveTileSpec(owner, project, stack, tileSpec);

        final TileSpec updatedTileSpec = dao.getTileSpec(owner, project, stack, z, tileId);

        Assert.assertNotNull("null tileSpec retrieved after update", updatedTileSpec);
        final LayoutData updatedLayoutData = updatedTileSpec.getLayout();
        Assert.assertNotNull("null layout retrieved after update", updatedLayoutData);
        Assert.assertEquals("invalid temca retrieved after update", changedTemca, updatedLayoutData.getTemca());
        Assert.assertTrue("tileSpec is missing transforms after update", tileSpec.hasTransforms());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveTileSpecWithBadTransformReference() throws Exception {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setZ(12.3);
        tileSpec.setTileId("bad-ref-tile");
        final List<TransformSpec> list = new ArrayList<TransformSpec>();
        list.add(new ReferenceTransformSpec("missing-id"));
        tileSpec.addTransformSpecs(list);

        dao.saveTileSpec(owner, project, stack, tileSpec);
    }


    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDaoTest.class);
}
