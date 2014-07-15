package org.janelia.render.service;

import org.janelia.alignment.RenderParameters;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Tests the {@link RenderParametersDao} class.
 *
 * @author Eric Trautman
 */
public class RenderParametersDaoTest {

    private static RenderParametersDao dao;

    @BeforeClass
    public static void before() throws Exception {
        dao = new RenderParametersDao(new File("src/test/resources/parameters"));
    }

    @Test
    public void testGetParameters() throws Exception {

        final String project = "test-project";
        final String stack = "stack-a";
        final Integer x = 0;
        final Integer y = 0;
        final Integer width = 4576;
        final Integer height = 4173;

        final RenderParameters parameters = dao.getParameters(project, stack, x, y, width, height);

        Assert.assertNotNull("null parameters retrieved", parameters);
        Assert.assertEquals("invalid width parsed", width.intValue(), parameters.getWidth());

        // validate that dao parameters can be re-serialized
        try {
            final String json = RenderParameters.DEFAULT_GSON.toJson(parameters);
            Assert.assertNotNull("null json string produced for parameters", json);
        } catch (Exception e) {
            LOG.error("failed to serialize json for " + parameters, e);
            Assert.fail("retrieved parameters cannot be re-serialized to json");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDaoTest.class);
}
