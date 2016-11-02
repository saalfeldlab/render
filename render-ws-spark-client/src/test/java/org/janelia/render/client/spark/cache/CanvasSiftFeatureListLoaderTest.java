package org.janelia.render.client.spark.cache;

import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.match.CanvasSiftFeatureExtractor;
import org.janelia.alignment.match.CanvasId;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CanvasSiftFeatureListLoader} class.
 *
 * @author Eric Trautman
 */
public class CanvasSiftFeatureListLoaderTest {

    @Test
    public void testGetRenderParametersUrl() throws Exception {

        testTemplate("http://render:8080/render-ws/v1/tile/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/tile/aaa/render-parameters");

        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/render-parameters");

        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/tile/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/tile/aaa/render-parameters");
    }

    private void testTemplate(final String template,
                              final String expectedResult) {

        final CanvasSiftFeatureExtractor extractor =
                new CanvasSiftFeatureExtractor(new FloatArray2DSIFT.Param(), 0.0, 0.0, true);

        final CanvasSiftFeatureListLoader loader = new CanvasSiftFeatureListLoader(template, extractor);

        final CanvasId canvasId = new CanvasId("99.0", "aaa");

        Assert.assertEquals("failed to parse template " + template,
                            expectedResult,
                            loader.getRenderParametersUrl(canvasId));
    }
}