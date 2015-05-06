package org.janelia.render.service.dao;

import java.io.File;
import java.util.List;

import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.test.EmbeddedMongoDb;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the {@link RenderDao} class.
 *
 * @author Eric Trautman
 */
public class RenderDaoRemoveStackTest {

    private static StackId stackId;
    private static EmbeddedMongoDb embeddedMongoDb;
    private static RenderDao dao;

    @BeforeClass
    public static void before() throws Exception {
        stackId = new StackId("flyTEM", "test", "elastic");
        embeddedMongoDb = new EmbeddedMongoDb(RenderDao.RENDER_DB_NAME);
        dao = new RenderDao(embeddedMongoDb.getMongoClient());

        embeddedMongoDb.importCollection(RenderDao.STACK_META_DATA_COLLECTION_NAME,
                                         new File("src/test/resources/mongodb/admin__stack_meta_data.json"),
                                         true,
                                         false,
                                         true);

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
    public void testRemoveStack() throws Exception {

        final StackMetaData stackMetaBeforeRemove = dao.getStackMetaData(stackId);
        Assert.assertNotNull("meta data for " + stackId + " missing before removal", stackMetaBeforeRemove);

        final List<Double> zValuesBeforeRemove = dao.getZValues(stackId);
        Assert.assertNotNull("zValues null for " + stackId + " before removal",
                             zValuesBeforeRemove);
        Assert.assertTrue("zValues missing for " + stackId + " before removal",
                          zValuesBeforeRemove.size() > 0);

        dao.removeStack(stackId);
        final StackMetaData stackMetaAfterRemove = dao.getStackMetaData(stackId);

        Assert.assertNull("meta data for " + stackId + " returned after removal", stackMetaAfterRemove);

        final List<Double> zValuesAfterRemove = dao.getZValues(stackId);
        Assert.assertNotNull("zValues null for " + stackId + " after removal",
                             zValuesAfterRemove);
        Assert.assertEquals("zValues exist for " + stackId + " after removal",
                            0, zValuesAfterRemove.size());
    }

}
