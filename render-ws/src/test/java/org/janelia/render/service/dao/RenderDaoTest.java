package org.janelia.render.service.dao;

import mpicbg.trakem2.transform.AffineModel2D;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ReferenceTransformSpec;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.render.service.ObjectNotFoundException;
import org.janelia.render.service.StackId;
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
 * Tests the {@link RenderDao} class.
 *
 * @author Eric Trautman
 */
public class RenderDaoTest {

    private static StackId stackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDao dao;

    @BeforeClass
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());

        embeddedMongoDb.importCollection(stackId.getTileCollectionName(),
                                         new File("src/test/resources/mongodb/elastic-3903.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(stackId.getTransformCollectionName(),
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
        final Double scale = 0.5;

        final RenderParameters parameters = dao.getParameters(stackId, x, y, z, width, height, scale);

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
        final TileSpec tileSpec = dao.getTileSpec(stackId, existingTileId, false);
        Assert.assertNotNull("null tileSpec retrieved", tileSpec);
        Assert.assertEquals("invalid tileId retrieved", existingTileId, tileSpec.getTileId());
    }

    @Test
    public void testGetTileSpecs() throws Exception {
        final List<TileSpec> list = dao.getTileSpecs(stackId, 3903.0);
        Assert.assertNotNull("null tile spec list retrieved", list);
        Assert.assertEquals("invalid number of tile specs retrieved", 12, list.size());
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTileSpecWithBadId() throws Exception {
        dao.getTileSpec(stackId, "missingId", false);
    }

    @Test
    public void testSaveTileSpec() throws Exception {
        final String tileId = "new-tile-1";
        final String temca = "0";
        final LayoutData layoutData = new LayoutData(123, temca, null, null, null, null, null, null);

        TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(tileId);
        tileSpec.setLayout(layoutData);

        dao.saveTileSpec(stackId, tileSpec);

        final TileSpec insertedTileSpec = dao.getTileSpec(stackId, tileId, false);

        Assert.assertNotNull("null tileSpec retrieved after insert", insertedTileSpec);
        final LayoutData insertedLayoutData = insertedTileSpec.getLayout();
        Assert.assertNotNull("null layout retrieved after insert", insertedLayoutData);
        Assert.assertEquals("invalid temca retrieved after insert", temca, insertedLayoutData.getTemca());
        Assert.assertFalse("tileSpec is has transforms after insert", tileSpec.hasTransforms());

        final String changedTemca = "1";
        final LayoutData changedLayoutData = new LayoutData(123, changedTemca, null, null, null, null, null, null);
        tileSpec.setLayout(changedLayoutData);
        final List<TransformSpec> list = new ArrayList<TransformSpec>();
        list.add(new ReferenceTransformSpec("1"));
        tileSpec.addTransformSpecs(list);

        dao.saveTileSpec(stackId, tileSpec);

        final TileSpec updatedTileSpec = dao.getTileSpec(stackId, tileId, false);

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

        dao.saveTileSpec(stackId, tileSpec);
    }

    @Test
    public void testGetTransformSpec() throws Exception {
        final TransformSpec transformSpec = dao.getTransformSpec(stackId, "2");
        Assert.assertNotNull("null transformSpec retrieved", transformSpec);
        Assert.assertEquals("invalid type retrieved", "list", transformSpec.getType());
    }

    @Test(expected = ObjectNotFoundException.class)
    public void testGetTransformSpecWithBadId() throws Exception {
        dao.getTransformSpec(stackId, "missingId");
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
        dao.saveTransformSpec(stackId, leafSpec);

        final TransformSpec insertedSpec = dao.getTransformSpec(stackId, transformId);

        Assert.assertNotNull("null transformSpec retrieved after insert", insertedSpec);
        final TransformSpecMetaData insertedMetaData = insertedSpec.getMetaData();
        Assert.assertNotNull("null meta data retrieved after insert", insertedMetaData);
        Assert.assertEquals("invalid group retrieved after insert",
                            metaData.getGroup(), insertedMetaData.getGroup());

        final TransformSpecMetaData changedMetaData = new TransformSpecMetaData();
        changedMetaData.setGroup("updated-group");

        final ListTransformSpec listSpec = new ListTransformSpec(transformId, changedMetaData);
        listSpec.addSpec(new ReferenceTransformSpec("1"));

        dao.saveTransformSpec(stackId, listSpec);

        final TransformSpec updatedSpec = dao.getTransformSpec(stackId, transformId);

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

        dao.saveTransformSpec(stackId, listSpec);
    }

    @Test
    public void testGetStackIds() throws Exception {
        final List<StackId> list = dao.getStackIds(stackId.getOwner());

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of stack ids found", 1, list.size());
    }

    @Test
    public void testGetZValues() throws Exception {
        final List<Double> list = dao.getZValues(stackId);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of layers found", 1, list.size());
    }

    @Test
    public void testGetLayerAndStackBounds() throws Exception {
        final Double expectedMinX = 1094.0;
        final Double expectedMinY = 1769.0;
        final Double expectedMaxX = 9917.0;
        final Double expectedMaxY = 8301.0;

        final Double z = 3903.0;

        Bounds bounds = dao.getLayerBounds(stackId, z);

        Assert.assertNotNull("null layer bounds retrieved", bounds);
        Assert.assertEquals("invalid layer minX", expectedMinX, bounds.getMinX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer minY", expectedMinY, bounds.getMinY(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxX", expectedMaxX, bounds.getMaxX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid layer maxY", expectedMaxY, bounds.getMaxY(), BOUNDS_DELTA);

        bounds = dao.getStackBounds(stackId);

        Assert.assertNotNull("null stack bounds retrieved", bounds);
        // TODO: find out why embedded mongo returns null min value for these queries
//        Assert.assertEquals("invalid stack minX", expectedMinX, bounds.getMinX(), BOUNDS_DELTA);
//        Assert.assertEquals("invalid stack minY", expectedMinY, bounds.getMinY(), BOUNDS_DELTA);
        Assert.assertEquals("invalid stack maxX", expectedMaxX, bounds.getMaxX(), BOUNDS_DELTA);
        Assert.assertEquals("invalid stack maxY", expectedMaxY, bounds.getMaxY(), BOUNDS_DELTA);
    }

    @Test
    public void testGetTileBounds() throws Exception {
        final Double z = 3903.0;
        final List<TileBounds> list = dao.getTileBounds(stackId, z);

        Assert.assertNotNull("null list retrieved", list);
        Assert.assertEquals("invalid number of tiles found", 12, list.size());

        final TileBounds firstTileBounds = list.get(0);

        Assert.assertNotNull("null bounds for first tile", firstTileBounds);
        Assert.assertEquals("incorrect id for first tile", "134", firstTileBounds.getTileId());
        Assert.assertTrue("bound box not defined for first tile", firstTileBounds.isBoundingBoxDefined());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDaoTest.class);
    private static final Double BOUNDS_DELTA = 0.1;
}
