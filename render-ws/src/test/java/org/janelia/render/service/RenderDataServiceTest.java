package org.janelia.render.service;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.dao.RenderDao;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Tests the {@link RenderDataService} class.
 *
 * @author Eric Trautman
 */
public class RenderDataServiceTest {

    private static StackId alignStackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDao dao;
    private static RenderDataService service;

    @BeforeClass
    public static void before() throws Exception {
        alignStackId = new StackId("flyTEM", "fly863", "align");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());
        service = new RenderDataService(dao);

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
    public void testGetAndSaveResolvedTiles() throws Exception {

        final ResolvedTileSpecCollection resolvedTiles = service.getResolvedTiles(alignStackId.getOwner(),
                                                                                  alignStackId.getProject(),
                                                                                  alignStackId.getStack(),
                                                                                  Z);

        validateResolvedTiles("before save", resolvedTiles, 1, 1);
        validateStackIdCount("before save", alignStackId.getOwner(), 1);

        final LeafTransformSpec leafTransformSpecA = new LeafTransformSpec("test_transform_a",
                                                                          null,
                                                                          "mpicbg.trakem2.transform.AffineModel2D",
                                                                          "1  0  0  1  0  0");
        resolvedTiles.addTransformSpecToCollection(leafTransformSpecA);
        resolvedTiles.addReferenceTransformToAllTiles(leafTransformSpecA.getId());

        final StackId testStackId = new StackId(alignStackId.getOwner(), alignStackId.getProject(), "test");

        final UriInfo uriInfo = new UriInfoImpl(new URI("http://test/resolvedTiles"),
                                                new URI("http://test"),
                                                "/resolvedTiles",
                                                "",
                                                new ArrayList<PathSegment>());

        service.saveResolvedTiles(testStackId.getOwner(),
                                  testStackId.getProject(),
                                  testStackId.getStack(),
                                  Z,
                                  uriInfo,
                                  resolvedTiles);

        validateStackIdCount("after save", testStackId.getOwner(), 2);

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
        tileSpecB.addTransformSpecs(Arrays.asList(leafTransformSpecB));

        resolvedTestTiles.addTileSpecToCollection(tileSpecB);

        service.saveResolvedTiles(testStackId.getOwner(),
                                  testStackId.getProject(),
                                  testStackId.getStack(),
                                  Z,
                                  uriInfo,
                                  resolvedTestTiles);

        validateStackIdCount("after second save", testStackId.getOwner(), 2);

        final ResolvedTileSpecCollection resolvedTest2Tiles = service.getResolvedTiles(testStackId.getOwner(),
                                                                                       testStackId.getProject(),
                                                                                       testStackId.getStack(),
                                                                                       Z);

        validateResolvedTiles("after second save", resolvedTest2Tiles, 2, 2);
    }

    private void validateResolvedTiles(String context,
                                       ResolvedTileSpecCollection resolvedTiles,
                                       int expectedNumberOfTileSpecs,
                                       int expectedNumberOfTransformSpecs) {
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

    private void validateStackIdCount(String context,
                                      String owner,
                                      int expectedNumberOfStackIds) {
        final List<StackId> stackIds = dao.getStackIds(owner);
        Assert.assertNotNull(context + ", stack ids are null",
                             stackIds);
        Assert.assertEquals(context + ", invalid number of stack ids",
                            expectedNumberOfStackIds, stackIds.size());
    }

    private static final Double Z = 2337.0;
}
