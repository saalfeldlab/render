package org.janelia.render.service.dao;

import java.util.Date;
import java.util.List;

import org.janelia.render.service.model.CollectionSnapshot;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the {@link AdminDao} class.
 *
 * @author Eric Trautman
 */
public class AdminDaoTest {

    private static EmbeddedMongoDb embeddedMongoDb;
    private static AdminDao dao;

    @BeforeClass
    public static void before() throws Exception {
        embeddedMongoDb = new EmbeddedMongoDb(AdminDao.ADMIN_DB_NAME);
        dao = new AdminDao(embeddedMongoDb.getMongoClient());
    }

    @AfterClass
    public static void after() throws Exception {
        embeddedMongoDb.stop();
    }

    @Test
    public void testSnapshotMethods() throws Exception {

        List<CollectionSnapshot> list = dao.getSnapshots(null, null, false);

        validateList("before first save with no criteria",
                     0, list);

        final CollectionSnapshot snapshot1 = new CollectionSnapshot("db1",
                                                                    "c1",
                                                                    0,
                                                                    "/snapshot/root",
                                                                    new Date(),
                                                                    "test 1",
                                                                    111L);

        list = dao.getSnapshots(snapshot1.getDatabaseName(), snapshot1.getCollectionName(), false);

        validateList("before first save with snapshot1 database and collection criteria",
                     0, list);

        dao.saveSnapshot(snapshot1);

        list = dao.getSnapshots(snapshot1.getDatabaseName(), snapshot1.getCollectionName(), false);

        validateList("after first save with snapshot1 database and collection criteria",
                     1, list);
        validateSnapshot("after first save with snapshot1 database and collection criteria",
                         snapshot1, list.get(0));

        final CollectionSnapshot snapshot1WithPersistence =
                snapshot1.getSnapshotWithPersistenceData(new Date(),
                                                         snapshot1.getFullPath() + "/full/path.bson",
                                                         222L);

        dao.saveSnapshot(snapshot1WithPersistence);

        final CollectionSnapshot snapshot2 = new CollectionSnapshot(snapshot1.getDatabaseName(),
                                                                    snapshot1.getCollectionName(),
                                                                    2,
                                                                    snapshot1.getRootPath(),
                                                                    new Date(),
                                                                    "test 2",
                                                                    333L);
        dao.saveSnapshot(snapshot2);

        final CollectionSnapshot snapshot3 = new CollectionSnapshot(snapshot1.getDatabaseName(),
                                                                    "c2",
                                                                    0,
                                                                    snapshot1.getRootPath(),
                                                                    new Date(),
                                                                    "test 3",
                                                                    444L);
        dao.saveSnapshot(snapshot3);

        final CollectionSnapshot snapshot4 = new CollectionSnapshot("d3",
                                                                    "c3",
                                                                    0,
                                                                    snapshot1.getRootPath(),
                                                                    new Date(),
                                                                    "test 3",
                                                                    444L);
        dao.saveSnapshot(snapshot4);

        list = dao.getSnapshots(null, null, false);
        validateList("after second saves with no criteria",
                     4, list);

        list = dao.getSnapshots(snapshot1.getDatabaseName(), null, false);
        validateList("after second saves with snapshot1 database criteria",
                     3, list);

        list = dao.getSnapshots(snapshot3.getDatabaseName(), snapshot3.getCollectionName(), false);
        validateList("after second saves with snapshot3 database and collection criteria",
                     1, list);

        list = dao.getSnapshots(snapshot1.getDatabaseName(), snapshot1.getCollectionName(), true);
        validateList("after second saves with snapshot1 database, collection, and filter criteria",
                     1, list);
        validateSnapshot("after second saves",
                         snapshot2, list.get(0));

    }

    private void validateList(String context,
                              int expectedSize,
                              List<CollectionSnapshot> list) {
        Assert.assertNotNull("null list returned " + context, list);
        Assert.assertEquals("invalid list size " + context, expectedSize, list.size());
    }

    private void validateSnapshot(String context,
                                  CollectionSnapshot expected,
                                  CollectionSnapshot actual) {
        Assert.assertNotNull("null snapshot " + context, actual);
        Assert.assertEquals("invalid databaseName " + context,
                            expected.getDatabaseName(), actual.getDatabaseName());
        Assert.assertEquals("invalid collectionName " + context,
                            expected.getCollectionName(), actual.getCollectionName());
        Assert.assertEquals("invalid version " + context,
                            expected.getVersion(), actual.getVersion());
        Assert.assertEquals("invalid rootPath " + context,
                            expected.getRootPath(), actual.getRootPath());
        Assert.assertEquals("invalid collectionCreateDate " + context,
                            expected.getCollectionCreateDate(), actual.getCollectionCreateDate());
        Assert.assertEquals("invalid versionNotes " + context,
                            expected.getVersionNotes(), actual.getVersionNotes());
        Assert.assertEquals("invalid estimatedBytes " + context,
                            expected.getEstimatedBytes(), actual.getEstimatedBytes());
        Assert.assertEquals("invalid snapshotDate " + context,
                            expected.getSnapshotDate(), actual.getSnapshotDate());
        Assert.assertEquals("invalid fullPath " + context,
                            expected.getFullPath(), actual.getFullPath());
        Assert.assertEquals("invalid actualBytes " + context,
                            expected.getActualBytes(), actual.getActualBytes());
    }

}
