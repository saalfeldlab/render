package org.janelia.render.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.UriInfo;

import org.janelia.render.service.dao.AdminDao;
import org.janelia.render.service.dao.AdminDaoTest;
import org.janelia.render.service.model.CollectionSnapshot;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.test.EmbeddedMongoDb;
import org.jboss.resteasy.specimpl.UriInfoImpl;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the {@link AdminService} class.
 *
 * @author Eric Trautman
 */
public class AdminServiceTest {

    private static EmbeddedMongoDb embeddedMongoDb;
    private static AdminService service;

    @BeforeClass
    public static void before() throws Exception {
        embeddedMongoDb = new EmbeddedMongoDb(AdminDao.ADMIN_DB_NAME);
        final AdminDao dao = new AdminDao(embeddedMongoDb.getMongoClient());

        service = new AdminService(dao);
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testSnapshotAPIs() throws Exception {

        final CollectionSnapshot snapshot1 = new CollectionSnapshot("testOwner",
                                                                    "testProject",
                                                                    "db1",
                                                                    "c1",
                                                                    0,
                                                                    "/snapshot/root",
                                                                    new Date(),
                                                                    "test 1",
                                                                    111L);

        List<CollectionSnapshot> list = service.getSnapshots(null, null, null, null);
        AdminDaoTest.validateList("before save", 0, list);

        try {
            service.getSnapshot(snapshot1.getOwner(),
                                snapshot1.getDatabaseName(),
                                snapshot1.getCollectionName(),
                                snapshot1.getVersion());
            Assert.fail("snapshot data should not exist for " + snapshot1);
        } catch (ObjectNotFoundException e) {
            Assert.assertTrue(true); // test passed
        }

        final UriInfo uriInfo = new UriInfoImpl(new URI("http://test/stack"),
                                                new URI("http://test"),
                                                "/stack",
                                                "",
                                                new ArrayList<PathSegment>());

        try {
            service.saveSnapshot(snapshot1.getOwner(),
                                 snapshot1.getDatabaseName(),
                                 "bad_" + snapshot1.getCollectionName(),
                                 snapshot1.getVersion(),
                                 uriInfo,
                                 snapshot1);
            Assert.fail("save of inconsistent snapshot data should have failed");
        } catch (IllegalServiceArgumentException e) {
            Assert.assertTrue(true); // test passed
        }

        service.saveSnapshot(snapshot1.getOwner(),
                             snapshot1.getDatabaseName(),
                             snapshot1.getCollectionName(),
                             snapshot1.getVersion(),
                             uriInfo,
                             snapshot1);

        CollectionSnapshot retrievedSnapshot =
                service.getSnapshot(snapshot1.getOwner(),
                                    snapshot1.getDatabaseName(),
                                    snapshot1.getCollectionName(),
                                    snapshot1.getVersion());

        AdminDaoTest.validateSnapshot("in retrieved snapshot", snapshot1, retrievedSnapshot);

        list = service.getSnapshots(null, null, null, true);
        AdminDaoTest.validateList("after save", 1, list);
        AdminDaoTest.validateSnapshot("in retrieved snapshot list", snapshot1, list.get(0));
    }

}
