package org.janelia.render.service.dao;

import mpicbg.trakem2.transform.AffineModel2D;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
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
        final String existingTileId = "134";
        final TileSpec tileSpec = dao.getTileSpec(owner, project, stack, existingTileId);
        Assert.assertNotNull("null tileSpec retrieved", tileSpec);
        Assert.assertEquals("invalid tileId retrieved", existingTileId, tileSpec.getTileId());
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTileSpecWithBadId() throws Exception {
        dao.getTileSpec(owner, project, stack, "missingId");
    }

    @Test
    public void testSaveTileSpec() throws Exception {
        final String tileId = "new-tile-1";
        final String temca = "0";
        final LayoutData layoutData = new LayoutData(temca, null, null, null);

        TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);
        tileSpec.setLayout(layoutData);

        dao.saveTileSpec(owner, project, stack, tileSpec);

        final TileSpec insertedTileSpec = dao.getTileSpec(owner, project, stack, tileId);

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

        final TileSpec updatedTileSpec = dao.getTileSpec(owner, project, stack, tileId);

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

    @Test
    public void testGetTransformSpec() throws Exception {
        final TransformSpec transformSpec = dao.getTransformSpec(owner, project, stack, "2");
        Assert.assertNotNull("null transformSpec retrieved", transformSpec);
        Assert.assertEquals("invalid type retrieved", "list", transformSpec.getType());
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTransformSpecWithBadId() throws Exception {
        dao.getTransformSpec(owner, project, stack, "missingId");
    }

    @Test
    public void testSaveTransformSpec() throws Exception {

        final String transformId = "new-transform-1";
        final TransformSpecMetaData metaData = new TransformSpecMetaData();
        metaData.setGroup("test-group");

        final LeafTransformSpec leafSpec = new LeafTransformSpec(transformId,
                                                                 metaData,
                                                                 AffineModel2D.class.getName(),
                                                                 "1  0  0  1  0  0");
        dao.saveTransformSpec(owner, project, stack, leafSpec);

        final TransformSpec insertedSpec = dao.getTransformSpec(owner, project, stack, transformId);

        Assert.assertNotNull("null transformSpec retrieved after insert", insertedSpec);
        final TransformSpecMetaData insertedMetaData = insertedSpec.getMetaData();
        Assert.assertNotNull("null meta data retrieved after insert", insertedMetaData);
        Assert.assertEquals("invalid group retrieved after insert",
                            metaData.getGroup(), insertedMetaData.getGroup());

        final TransformSpecMetaData changedMetaData = new TransformSpecMetaData();
        changedMetaData.setGroup("updated-group");

        final ListTransformSpec listSpec = new ListTransformSpec(transformId, changedMetaData);
        listSpec.addSpec(new ReferenceTransformSpec("1"));

        dao.saveTransformSpec(owner, project, stack, listSpec);

        final TransformSpec updatedSpec = dao.getTransformSpec(owner, project, stack, transformId);

        Assert.assertNotNull("null transformSpec retrieved after update", updatedSpec);
        final TransformSpecMetaData updatedMetaData = updatedSpec.getMetaData();
        Assert.assertNotNull("null meta data retrieved after update", updatedMetaData);
        Assert.assertEquals("invalid group retrieved after update",
                            changedMetaData.getGroup(), updatedMetaData.getGroup());
        Assert.assertFalse("transformSpec should not be resolved after update", updatedSpec.isFullyResolved());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveTransformSpecWithBadTransformReference() throws Exception {
        final ListTransformSpec listSpec = new ListTransformSpec("bad-ref-transform", null);
        listSpec.addSpec(new ReferenceTransformSpec("missing-id"));

        dao.saveTransformSpec(owner, project, stack, listSpec);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDaoTest.class);
}
