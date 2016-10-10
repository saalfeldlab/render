package org.janelia.render.client.spark;

import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link RenderableCanvasIdPairsUtilities} class.
 *
 * @author Eric Trautman
 */
public class RenderableCanvasIdPairsUtilitiesTest {

    @Test
    public void testLoad() throws Exception {

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                RenderableCanvasIdPairsUtilities.load("src/test/resources/tile_pairs_v12_acquire_merged_1_5.json");

        Assert.assertNotNull("pairs not loaded", renderableCanvasIdPairs);

        Assert.assertEquals("incorrect number of pairs loaded", 4722, renderableCanvasIdPairs.size());

        final String baseDataUrl = "http://render/render-ws/v1";
        String templateForRun =
                RenderableCanvasIdPairsUtilities.getRenderParametersUrlTemplateForRun(renderableCanvasIdPairs,
                                                                                      baseDataUrl,
                                                                                      null,
                                                                                      null,
                                                                                      1.0,
                                                                                      false,
                                                                                      false);
        Assert.assertEquals("invalid template derived for basic run",
                            baseDataUrl + "/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters?" +
                            "normalizeForMatching=true",
                            templateForRun);

        templateForRun =
                RenderableCanvasIdPairsUtilities.getRenderParametersUrlTemplateForRun(renderableCanvasIdPairs,
                                                                                      baseDataUrl,
                                                                                      2760,
                                                                                      2330,
                                                                                      0.8,
                                                                                      true,
                                                                                      true);
        Assert.assertEquals("invalid template derived for scaled run",
                            baseDataUrl + "/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/tile/{id}/render-parameters?" +
                            "width=2760&height=2330&scale=0.8&filter=true&excludeMask=true&normalizeForMatching=true",
                            templateForRun);
    }

}