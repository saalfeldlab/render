package org.janelia.render.service;

import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.LastTileTransform;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.RenderQueryParameters;
import org.janelia.test.EmbeddedMongoDb;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests the {@link RenderDataService} class.
 *
 * @author Eric Trautman
 */
public class RenderDataServiceTest {

    private static StackId alignStackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDataService service;

    @BeforeAll
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

    @AfterAll
    public static void after() {
        embeddedMongoDb.stop();
    }

    @Test
    public void testGetExternalRenderParameters() {

        final RenderQueryParameters renderQueryParameters = new RenderQueryParameters();
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
                                                    renderQueryParameters);

        assertNotNull(renderParameters, "null parameters returned");
    }

    @Test
    public void testGetAndSaveResolvedTiles() throws Exception {

        final ResolvedTileSpecCollection resolvedTiles = service.getResolvedTiles(alignStackId.getOwner(),
                                                                                  alignStackId.getProject(),
                                                                                  alignStackId.getStack(),
                                                                                  Z,
                                                                                  null);

        validateResolvedTiles("before save", resolvedTiles, 1, 1);

        final LeafTransformSpec leafTransformSpecA = new LeafTransformSpec("test_transform_a",
                                                                          null,
                                                                          "mpicbg.trakem2.transform.AffineModel2D",
                                                                          "1  0  0  1  0  0");
        resolvedTiles.addTransformSpecToCollection(leafTransformSpecA);
        resolvedTiles.addReferenceTransformToAllTiles(leafTransformSpecA.getId(),
                                                      ResolvedTileSpecCollection.TransformApplicationMethod.APPEND);

        final StackId testStackId = new StackId(alignStackId.getOwner(), alignStackId.getProject(), "test");

        final UriInfo uriInfo = new ResteasyUriInfo(new URI("http://test/resolvedTiles"),
                                                    new URI("http://test"));

        //noinspection EmptyTryBlock,unused
        try (final Response response = service.saveResolvedTilesForZ(testStackId.getOwner(),
                                                                     testStackId.getProject(),
                                                                     testStackId.getStack(),
                                                                     Z,
                                                                     null,
                                                                     uriInfo,
                                                                     resolvedTiles)) {}


        final ResolvedTileSpecCollection resolvedTestTiles = service.getResolvedTiles(testStackId.getOwner(),
                                                                                      testStackId.getProject(),
                                                                                      testStackId.getStack(),
                                                                                      Z,
                                                                                      null);

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

        //noinspection EmptyTryBlock,unused
        try (final Response response = service.saveResolvedTilesForZ(testStackId.getOwner(),
                                                                     testStackId.getProject(),
                                                                     testStackId.getStack(),
                                                                     Z,
                                                                     null,
                                                                     uriInfo,
                                                                     resolvedTestTiles)) {}

        final ResolvedTileSpecCollection resolvedTest2Tiles = service.getResolvedTiles(testStackId.getOwner(),
                                                                                       testStackId.getProject(),
                                                                                       testStackId.getStack(),
                                                                                       Z,
                                                                                       null);

        validateResolvedTiles("after second save", resolvedTest2Tiles, 2, 2);
    }

    @Test
    public void testGetLastTileTransforms() {

        final List<LastTileTransform> lastTileTransformList =
                service.getLastTileTransformsForZ(alignStackId.getOwner(),
                                                  alignStackId.getProject(),
                                                  alignStackId.getStack(),
                                                  2337.0,
                                                  null);

        assertNotNull(lastTileTransformList, "null list returned");
        assertEquals(1, lastTileTransformList.size(), "invalid number of tiles");
        final LastTileTransform firstEntry = lastTileTransformList.getFirst();
        assertEquals("140331142443008104", firstEntry.tileId(), "invalid tileId for first entry");
        assertEquals(LeafTransformSpec.class, firstEntry.lastTransform().getClass(),
                     "invalid last transform class");
    }

    private void validateResolvedTiles(final String context,
                                       final ResolvedTileSpecCollection resolvedTiles,
                                       final int expectedNumberOfTileSpecs,
                                       final int expectedNumberOfTransformSpecs) {
        assertNotNull(resolvedTiles,
                      context + ", null resolved tiles returned");

        final Collection<TileSpec> tileSpecs = resolvedTiles.getTileSpecs();
        assertNotNull(tileSpecs,
                      context + ", tile specs are null");
        assertEquals(expectedNumberOfTileSpecs, tileSpecs.size(),
                     context + ", invalid number of tile specs");

        final Collection<TransformSpec> transformSpecs = resolvedTiles.getTransformSpecs();
        assertNotNull(transformSpecs,
                      context + ", transform specs are null");
        assertEquals(expectedNumberOfTransformSpecs, transformSpecs.size(),
                     context + ", invalid number of transform specs");
    }

    private static final Double Z = 2337.0;
}
