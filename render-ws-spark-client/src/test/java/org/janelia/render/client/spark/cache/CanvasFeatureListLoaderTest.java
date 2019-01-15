package org.janelia.render.client.spark.cache;

import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.render.client.cache.CanvasFeatureListLoader;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CanvasFeatureListLoader} class.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureListLoaderTest {

    private String tileId = "aaa";

    @Test
    public void testGetRenderParametersUrl() {


        testTemplate("http://render:8080/render-ws/v1/tile/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/tile/aaa/render-parameters");

        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/render-parameters");

        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/tile/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/tile/aaa/render-parameters");

        tileId = "z_1.0_box_12769_7558_13654_18227_0.1";
        testTemplate("http://render:8080/render-ws/v1/z/{groupId}/box/{id}/render-parameters",
                     "http://render:8080/render-ws/v1/z/99.0/box/12769,7558,13654,18227,0.1/render-parameters");
    }

    private void testTemplate(final String templateString,
                              final String expectedResult) {

        final CanvasRenderParametersUrlTemplate template = new CanvasRenderParametersUrlTemplate(templateString);

        final CanvasFeatureExtractor extractor =
                new CanvasFeatureExtractor(new FloatArray2DSIFT.Param(), 0.0, 0.0, true);

        final CanvasFeatureListLoader loader = new CanvasFeatureListLoader(template, extractor);

        final CanvasId canvasId = new CanvasId("99.0", tileId);

        Assert.assertEquals("failed to parse template " + template,
                            expectedResult,
                            loader.getRenderParametersUrl(canvasId));
    }
}