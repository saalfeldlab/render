package org.janelia.render.service;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.TileSpec;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

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
        final Double x = 0.0;
        final Double y = 0.0;
        final Integer z = 0;
        final Integer width = 4576;
        final Integer height = 4173;
        final Integer zoomLevel = 1;

        final RenderParameters parameters = dao.getParameters(project, stack, x, y, z, width, height, zoomLevel);

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
        Assert.assertEquals("invalid number of tiles after init", 4, tileSpecs.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderParametersDaoTest.class);
}
