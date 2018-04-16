package org.janelia.alignment.match;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CanvasRenderParametersUrlTemplate} class.
 *
 * @author Eric Trautman
 */
public class CanvasRenderParametersUrlTemplateTest {

    @Test
    public void testLoad() throws Exception {

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                RenderableCanvasIdPairs.load("src/test/resources/match-test/tile_pairs_v12_acquire_merged_1_5.json");

        Assert.assertNotNull("pairs not loaded", renderableCanvasIdPairs);

        Assert.assertEquals("incorrect number of pairs loaded", 4722, renderableCanvasIdPairs.size());

        final String baseDataUrl = "http://render/render-ws/v1";
        CanvasRenderParametersUrlTemplate templateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        null,
                        null,
                        1.0,
                        false,
                        null,
                        false);
        Assert.assertEquals("invalid template derived for basic run",
                            baseDataUrl + "/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters?" +
                            "normalizeForMatching=true",
                            templateForRun.getTemplateString());

        templateForRun =
                CanvasRenderParametersUrlTemplate.getTemplateForRun(
                        renderableCanvasIdPairs.getRenderParametersUrlTemplate(baseDataUrl),
                        2760,
                        2330,
                        0.8,
                        true,
                        "fav",
                        true);
        Assert.assertEquals("invalid template derived for scaled run",
                            baseDataUrl + "/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters?" +
                            "width=2760&height=2330&scale=0.8&filter=true&filterListName=fav&excludeMask=true&normalizeForMatching=true",
                            templateForRun.getTemplateString());
    }

}