package org.janelia.render.client.spark.cache;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasSurfFeatureExtractor;
import org.janelia.alignment.match.SurfFeatures;
import org.junit.Ignore;
import org.junit.Test;

import boofcv.struct.image.GrayF32;

/**
 * Tests the {@link CanvasSiftFeatureListLoader} class.
 *
 * Uncomment Ignore annotation below to run tests using JUnit.
 *
 * @author Eric Trautman
 */
@Ignore
public class CanvasSurfFeatureListLoaderTest {

    @Test
    public void testLoadForOnePair() throws Exception {

        final String renderParametersUrlTemplateForRun =
                "http://10.40.3.162:8080/render-ws/v1/owner/flyTEM/project/FAFB00/stack/v12_acquire_merged/" +
                "tile/{id}/render-parameters?scale=0.6&excludeMask=true&normalizeForMatching=true";

        final CanvasDataLoader canvasDataLoader =
                new CanvasSurfFeatureListLoader(renderParametersUrlTemplateForRun,
                                                new CanvasSurfFeatureExtractor<>(CanvasSurfFeatureExtractor.DEFAULT_CONFIG,
                                                                                 true,
                                                                                 GrayF32.class));

        final CanvasDataCache dataCache = CanvasDataCache.getSharedCache(100000,
                                                                         canvasDataLoader);

        final String groupId = "525.0";
        final String pTileId = "151217112333012136.525.0";
        final String qTileId = "151217112333013136.525.0";
        final SurfFeatures pSurfFeatures = dataCache.getSurfFeatureList(new CanvasId(groupId, pTileId));
        final SurfFeatures qSurfFeatures = dataCache.getSurfFeatureList(new CanvasId(groupId, qTileId));

        final SurfFeatures pSurfFeatures2 = dataCache.getSurfFeatureList(new CanvasId(groupId, pTileId));

        System.out.println("p: " + pSurfFeatures.size());
        System.out.println("q: " + qSurfFeatures.size());
        System.out.println("p2: " + pSurfFeatures2.size());

    }

}