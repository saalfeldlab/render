package org.janelia.render.service;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.core.UriInfo;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.LastTileTransform;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.test.EmbeddedMongoDb;
import org.jboss.resteasy.spi.ResteasyUriInfo;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the {@link RenderDataService} class.
 *
 * @author Eric Trautman
 */
public class RenderDataServiceTest {

    private static StackId alignStackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDataService service;

    @BeforeClass
    public static void before() throws Exception {
        alignStackId = new StackId("flyTEM", "fly863", "align");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        final RenderDao dao = new RenderDao(embeddedMongoDb.getMongoClient());
        service = new RenderDataService(dao);

        embeddedMongoDb.importCollection(RenderDao.STACK_META_DATA_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/admin__stack_meta_data.json"),
                                         true,
                                         false,
                                         true);

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

    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetExternalRenderParameters() throws Exception {

        final RenderParameters renderParameters =
                service.getExternalRenderParameters(alignStackId.getOwner(),
                                                    alignStackId.getProject(),
                                                    alignStackId.getStack(),
                                                    100000.0,
                                                    17000.0,
                                                    2337.0,
                                                    200,
                                                    200,
                                                    1.0,
                                                    null,
                                                    null,
                                                    null,
                                                    null);

        Assert.assertNotNull("null parameters returned", renderParameters);
    }

    @Test
    public void testGetAndSaveResolvedTiles() throws Exception {

        final ResolvedTileSpecCollection resolvedTiles = service.getResolvedTiles(alignStackId.getOwner(),
                                                                                  alignStackId.getProject(),
                                                                                  alignStackId.getStack(),
                                                                                  Z);

        validateResolvedTiles("before save", resolvedTiles, 1, 1);

        final LeafTransformSpec leafTransformSpecA = new LeafTransformSpec("test_transform_a",
                                                                          null,
                                                                          "mpicbg.trakem2.transform.AffineModel2D",
                                                                          "1  0  0  1  0  0");
        resolvedTiles.addTransformSpecToCollection(leafTransformSpecA);
        resolvedTiles.addReferenceTransformToAllTiles(leafTransformSpecA.getId(), false);

        final StackId testStackId = new StackId(alignStackId.getOwner(), alignStackId.getProject(), "test");

        final UriInfo uriInfo = new ResteasyUriInfo(new URI("http://test/resolvedTiles"),
                                                    new URI("http://test"));

        service.saveResolvedTilesForZ(testStackId.getOwner(),
                                      testStackId.getProject(),
                                      testStackId.getStack(),
                                      Z,
                                      uriInfo,
                                      resolvedTiles);

        final ResolvedTileSpecCollection resolvedTestTiles = service.getResolvedTiles(testStackId.getOwner(),
                                                                                      testStackId.getProject(),
                                                                                      testStackId.getStack(),
                                                                                      Z);

        validateResolvedTiles("after save", resolvedTestTiles, 1, 2);

        final TransformSpec leafTransformSpecB = new LeafTransformSpec("test_transform_b",
                                                                       null,
                                                                       "mpicbg.trakem2.transform.AffineModel2D",
                                                                       "1  0  0  1  0  0");
        final TileSpec tileSpecB = new TileSpec();
        tileSpecB.setTileId("test_tile_b");
        tileSpecB.setZ(Z);
        tileSpecB.addTransformSpecs(Collections.singletonList(leafTransformSpecB));
        tileSpecB.setWidth(10.0);
        tileSpecB.setHeight(10.0);
        tileSpecB.deriveBoundingBox(tileSpecB.getMeshCellSize(), false);

        resolvedTestTiles.addTileSpecToCollection(tileSpecB);

        service.saveResolvedTilesForZ(testStackId.getOwner(),
                                      testStackId.getProject(),
                                      testStackId.getStack(),
                                      Z,
                                      uriInfo,
                                      resolvedTestTiles);

        final ResolvedTileSpecCollection resolvedTest2Tiles = service.getResolvedTiles(testStackId.getOwner(),
                                                                                       testStackId.getProject(),
                                                                                       testStackId.getStack(),
                                                                                       Z);

        validateResolvedTiles("after second save", resolvedTest2Tiles, 2, 2);
    }

    @Test
    public void testGetLastTileTransforms() throws Exception {

        final List<LastTileTransform> lastTileTransformList =
                service.getLastTileTransformsForZ(alignStackId.getOwner(),
                                                  alignStackId.getProject(),
                                                  alignStackId.getStack(),
                                                  2337.0);

        Assert.assertNotNull("null list returned", lastTileTransformList);
        Assert.assertEquals("invalid number of tiles", 1, lastTileTransformList.size());
        final LastTileTransform firstEntry = lastTileTransformList.get(0);
        Assert.assertEquals("invalid tileId for first entry", "140331142443008104", firstEntry.getTileId());
        Assert.assertEquals("invalid last transform class",
                            LeafTransformSpec.class, firstEntry.getLastTransform().getClass());
    }

    private void validateResolvedTiles(final String context,
                                       final ResolvedTileSpecCollection resolvedTiles,
                                       final int expectedNumberOfTileSpecs,
                                       final int expectedNumberOfTransformSpecs) {
        Assert.assertNotNull(context + ", null resolved tiles returned",
                             resolvedTiles);

        final Collection<TileSpec> tileSpecs = resolvedTiles.getTileSpecs();
        Assert.assertNotNull(context + ", tile specs are null",
                             tileSpecs);
        Assert.assertEquals(context + ", invalid number of tile specs",
                            expectedNumberOfTileSpecs, tileSpecs.size());

        final Collection<TransformSpec> transformSpecs = resolvedTiles.getTransformSpecs();
        Assert.assertNotNull(context + ", transform specs are null",
                             transformSpecs);
        Assert.assertEquals(context + ", invalid number of transform specs",
                            expectedNumberOfTransformSpecs, transformSpecs.size());
    }

    private static final Double Z = 2337.0;
}
