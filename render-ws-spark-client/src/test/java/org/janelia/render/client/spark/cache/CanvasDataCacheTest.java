package org.janelia.render.client.spark.cache;

import java.util.List;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;

import org.janelia.alignment.match.CanvasFeatureExtractor;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasRenderParametersUrlTemplate;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.cache.CachedCanvasFeatures;
import org.janelia.alignment.match.cache.CanvasDataCache;
import org.janelia.alignment.match.cache.CanvasFeatureListLoader;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CanvasDataCache} class.
 *
 * @author Eric Trautman
 */
public class CanvasDataCacheTest {

    @Test
    public void testCachedClipOffsets() {

        final FloatArray2DSIFT.Param siftParameters = new FloatArray2DSIFT.Param();
        siftParameters.fdSize = 8;
        siftParameters.steps = 3;

        final CanvasFeatureExtractor featureExtractor = new CanvasFeatureExtractor(siftParameters, 0.38, 0.82);

        final String templateString = "src/test/resources/canvas-render-parameters.json";

        final FeatureRenderClipParameters clipParameters = new FeatureRenderClipParameters();
        final int clipSize = 800;
        clipParameters.clipWidth = clipSize;
        clipParameters.clipHeight = clipSize;

        final CanvasRenderParametersUrlTemplate template =
                new CanvasRenderParametersUrlTemplate(templateString,
                                                      new FeatureRenderParameters(),
                                                      clipParameters);

        final long cacheMaxKilobytes = 100;
        final CanvasFeatureListLoader featureLoader = new CanvasFeatureListLoader(featureExtractor);

        final CanvasDataCache dataCache = CanvasDataCache.getSharedCache(cacheMaxKilobytes, featureLoader);

        final CanvasId q = new CanvasId("1148.0",
                                        "20171004212023032_295434_5LC_0064_reimaging_03_001050_0_17_49.1050.0.c1",
                                        MontageRelativePosition.LEFT);

        final CachedCanvasFeatures firstCallFeatures =
                dataCache.getCanvasFeatures(CanvasIdWithRenderContext.build(q, template));

        final List<Feature> firstCallFeatureList = firstCallFeatures.getFeatureList();
        final double[] firstCallClipOffsets = firstCallFeatures.getClipOffsets();

        Assert.assertTrue("first call: no features found", firstCallFeatureList.size() > 0);
        Assert.assertTrue("first call: x clip offset not set", firstCallClipOffsets[0] > 0);
        Assert.assertEquals("first call: invalid y clip offset", 0.0, firstCallClipOffsets[1], 0.01);

        final CachedCanvasFeatures secondCallFeatures =
                dataCache.getCanvasFeatures(CanvasIdWithRenderContext.build(q, template));

        final List<Feature> secondCallFeatureList = secondCallFeatures.getFeatureList();
        final double[] secondCallClipOffsets = secondCallFeatures.getClipOffsets();

        Assert.assertEquals("second call: invalid number of features",
                            firstCallFeatureList.size(), secondCallFeatureList.size());
        Assert.assertEquals("second call: invalid x clip offset",
                            firstCallClipOffsets[0], secondCallClipOffsets[0], 0.01);
        Assert.assertEquals("second call: invalid y clip offset",
                            firstCallClipOffsets[1], secondCallClipOffsets[1], 0.01);

    }

}