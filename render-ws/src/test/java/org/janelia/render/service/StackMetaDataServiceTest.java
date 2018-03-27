package org.janelia.render.service;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;

import javax.ws.rs.core.UriInfo;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.dao.RenderDaoTest;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.test.EmbeddedMongoDb;
import org.jboss.resteasy.spi.ResteasyUriInfo;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.COMPLETE;
import static org.janelia.alignment.spec.stack.StackMetaData.StackState.LOADING;

/**
 * Tests the {@link StackMetaDataService} class.
 *
 * @author Eric Trautman
 */
public class StackMetaDataServiceTest {

    private static StackId loadingStackId;
    private static StackId completeStackId;

    private static EmbeddedMongoDb embeddedMongoDb;
    private static StackMetaDataService service;
    private static RenderDao renderDao;

    @BeforeClass
    public static void before() throws Exception {
        loadingStackId = new StackId("flyTEM", "test_project", "test_stack");
        completeStackId = new StackId("flyTEM", "test", "elastic");

        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        renderDao = new RenderDao(embeddedMongoDb.getMongoClient());
        service = new StackMetaDataService(renderDao);
    }

    @Before
    public void setUp() throws Exception {
        embeddedMongoDb.importCollection(RenderDao.STACK_META_DATA_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/admin__stack_meta_data.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(completeStackId.getTileCollectionName(),
                                         new File("src/test/resources/mongodb/elastic-3903.json"),
                                         true,
                                         false,
                                         true);

        embeddedMongoDb.importCollection(completeStackId.getTransformCollectionName(),
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
    public void testStackMetaDataAPIs() throws Exception {

        try {
            service.getStackMetaData(loadingStackId.getOwner(),
                                     loadingStackId.getProject(),
                                     loadingStackId.getStack());
            Assert.fail("meta data should not exist for " + loadingStackId);
        } catch (final ObjectNotFoundException e) {
            Assert.assertTrue(true); // test passed
        }

        final StackVersion stackVersion0 = new StackVersion(new Date(),
                                                            "first try",
                                                            5,
                                                            3,
                                                            4.0,
                                                            4.0,
                                                            35.0,
                                                            null,
                                                            null);

        service.saveStackVersion(loadingStackId.getOwner(),
                                 loadingStackId.getProject(),
                                 loadingStackId.getStack(),
                                 getUriInfo(),
                                 stackVersion0);

        final StackMetaData stackMetaData0 = service.getStackMetaData(loadingStackId.getOwner(),
                                                                      loadingStackId.getProject(),
                                                                      loadingStackId.getStack());

        RenderDaoTest.validateStackMetaData(" for stack 0", LOADING, 0, stackVersion0, stackMetaData0);

        final StackVersion stackVersion1 = new StackVersion(new Date(),
                                                            "second try",
                                                            5,
                                                            3,
                                                            4.2,
                                                            4.2,
                                                            35.2,
                                                            null,
                                                            null);

        service.saveStackVersion(loadingStackId.getOwner(),
                                 loadingStackId.getProject(),
                                 loadingStackId.getStack(),
                                 getUriInfo(),
                                 stackVersion1);

        final StackMetaData stackMetaData1 = service.getStackMetaData(loadingStackId.getOwner(),
                                                                      loadingStackId.getProject(),
                                                                      loadingStackId.getStack());

        RenderDaoTest.validateStackMetaData(" for stack 1", LOADING, 1, stackVersion1, stackMetaData1);

        try {
            service.getStackBounds(loadingStackId.getOwner(),
                                   loadingStackId.getProject(),
                                   loadingStackId.getStack());
            Assert.fail("stack without stats should fail bounds request");
        } catch (final IllegalServiceArgumentException e) {
            Assert.assertTrue(true); // test passed
        }

        service.deleteStack(loadingStackId.getOwner(),
                            loadingStackId.getProject(),
                            loadingStackId.getStack());

        try {
            service.getStackMetaData(loadingStackId.getOwner(),
                                     loadingStackId.getProject(),
                                     loadingStackId.getStack());
            Assert.fail("after delete, meta data should not exist for " + loadingStackId);
        } catch (final ObjectNotFoundException e) {
            Assert.assertTrue(true); // test passed
        }
    }

    @Test
    public void testSetStackState() throws Exception {

        final StackMetaData stackMetaData1 = service.getStackMetaData(completeStackId.getOwner(),
                                                                      completeStackId.getProject(),
                                                                      completeStackId.getStack());

        try {
            service.getStackBounds(completeStackId.getOwner(),
                                   completeStackId.getProject(),
                                   completeStackId.getStack());
            Assert.fail("stack without bounds should fail bounds request");
        } catch (final IllegalServiceArgumentException e) {
            Assert.assertTrue(true); // test passed
        }


        service.setStackState(completeStackId.getOwner(),
                              completeStackId.getProject(),
                              completeStackId.getStack(),
                              COMPLETE,
                              getUriInfo());

        final StackMetaData stackMetaData2 = service.getStackMetaData(completeStackId.getOwner(),
                                                                      completeStackId.getProject(),
                                                                      completeStackId.getStack());

        RenderDaoTest.validateStackMetaData(" for stack 2",
                                            COMPLETE,
                                            stackMetaData1.getCurrentVersionNumber(),
                                            stackMetaData1.getCurrentVersion(),
                                            stackMetaData2);

        final StackStats stats = stackMetaData2.getStats();
        Assert.assertNotNull("stats not derived after setting state to complete", stats);

        final Bounds stackBounds = stats.getStackBounds();
        Assert.assertNotNull("stack bounds not derived", stackBounds);

        Assert.assertEquals("invalid stackBounds.minZ", 3903.0, stackBounds.getMinZ(), 0.01);

        Assert.assertEquals("invalid sectionCount", new Long(2), stats.getSectionCount());
        Assert.assertEquals("invalid nonIntegralSectionCount", new Long(1), stats.getNonIntegralSectionCount());
        Assert.assertEquals("invalid tileCount", new Long(14), stats.getTileCount());
        Assert.assertEquals("invalid transformCount", new Long(3), stats.getTransformCount());
        Assert.assertEquals("invalid minTileWidth", new Integer(2631), stats.getMinTileWidth());
        Assert.assertEquals("invalid maxTileWidth", new Integer(2772), stats.getMaxTileWidth());
        Assert.assertEquals("invalid minTileHeight", new Integer(2257), stats.getMinTileHeight());
        Assert.assertEquals("invalid maxTileHeight", new Integer(2414), stats.getMaxTileHeight());

        final Bounds stackBounds2 = service.getStackBounds(completeStackId.getOwner(),
                                                           completeStackId.getProject(),
                                                           completeStackId.getStack());
        Assert.assertNotNull("stack bounds not returned after storing stats", stackBounds2);
        Assert.assertEquals("invalid min Y returned after storing stats",
                            stackBounds.getMinY(), stackBounds2.getMinY(), 0.01);

        service.deleteStack(completeStackId.getOwner(),
                            completeStackId.getProject(),
                            completeStackId.getStack());

        try {
            service.getStackMetaData(completeStackId.getOwner(),
                                     completeStackId.getProject(),
                                     completeStackId.getStack());
            Assert.fail("after delete, meta data should not exist for " + completeStackId);
        } catch (final ObjectNotFoundException e) {
            Assert.assertTrue(true); // test passed
        }

    }

    @Test
    public void testCloneStackVersion() throws Exception {

        final StackId clonedStackId = new StackId(completeStackId.getOwner(),
                                                  completeStackId.getProject(),
                                                  "clonedStack");
        try {
            service.getStackMetaData(clonedStackId.getOwner(),
                                     clonedStackId.getProject(),
                                     clonedStackId.getStack());
            Assert.fail("before clone, meta data should not exist for " + clonedStackId);
        } catch (final ObjectNotFoundException e) {
            Assert.assertTrue(true); // test passed
        }

        final StackVersion clonedStackVersion = new StackVersion(new Date(),
                                                                 "cloned",
                                                                 1,
                                                                 2,
                                                                 3.1,
                                                                 4.1,
                                                                 5.1,
                                                                 null,
                                                                 null);

        service.cloneStackVersion(completeStackId.getOwner(),
                                  completeStackId.getProject(),
                                  completeStackId.getStack(),
                                  clonedStackId.getStack(),
                                  null,
                                  null,
                                  null,
                                  getUriInfo(),
                                  clonedStackVersion);

        final StackMetaData clonedStackMetaData = service.getStackMetaData(clonedStackId.getOwner(),
                                                                           clonedStackId.getProject(),
                                                                           clonedStackId.getStack());

        RenderDaoTest.validateStackMetaData(" for cloned stack", LOADING, 0, clonedStackVersion, clonedStackMetaData);
    }

    @Test
    public void testDeleteStackTilesWithZ() throws Exception {

        final Double z = 3903.0;

        try {
            service.deleteStackTilesWithZ(completeStackId.getOwner(),
                                          completeStackId.getProject(),
                                          completeStackId.getStack(),
                                          z);
            Assert.fail("tiles should not be deleted for stack in COMPLETE state");
        } catch (final IllegalServiceArgumentException e) {
            Assert.assertTrue(true); // test passed
        }

        final StackId clonedStackId = new StackId(completeStackId.getOwner(),
                                                  completeStackId.getProject(),
                                                  "clonedStack2");

        final StackVersion clonedStackVersion = new StackVersion(new Date(),
                                                                 "cloned2",
                                                                 1,
                                                                 2,
                                                                 3.1,
                                                                 4.1,
                                                                 5.1,
                                                                 null,
                                                                 null);

        service.cloneStackVersion(completeStackId.getOwner(),
                                  completeStackId.getProject(),
                                  completeStackId.getStack(),
                                  clonedStackId.getStack(),
                                  null,
                                  null,
                                  null,
                                  getUriInfo(),
                                  clonedStackVersion);

        List<TileSpec> specList = renderDao.getTileSpecs(clonedStackId, z);
        Assert.assertTrue("before removal, no tile specs exist for z " + z,
                          specList.size() > 0);


        service.deleteStackTilesWithZ(clonedStackId.getOwner(),
                                      clonedStackId.getProject(),
                                      clonedStackId.getStack(),
                                      z);

        try {
            specList = renderDao.getTileSpecs(clonedStackId, z);
            Assert.assertEquals("after removal, invalid number of tile specs exist for z " + z,
                                0, specList.size());
        } catch (final IllegalArgumentException e) {
            Assert.assertTrue(true); // test passed - no tile specs exist
        }

    }

    private static UriInfo getUriInfo()
            throws URISyntaxException {
        return new ResteasyUriInfo(new URI("http://test/stack"),
                                   new URI("http://test"));
    }

}
