package org.janelia.alignment;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.util.Timer;

import org.janelia.alignment.filter.EqualizeHistogram;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link RenderParameters} class.
 *
 * @author Eric Trautman
 */
public class RenderParametersTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final String url = "file:///Users/trautmane/renderer-test.json";
        final double x = 0.0;
        final double y = 1.0;
        final int width = 2;
        final int height = 3;
        final double scale = 0.125;

        final RenderParameters parameters = new RenderParameters(url, x, y, width, height, scale);

        final ChannelSpec channelSpec0 = new ChannelSpec();
        channelSpec0.putMipmap(0, new ImageAndMask("spec0-level0.png", null));
        channelSpec0.putMipmap(1, new ImageAndMask("spec0-level1.png", null));
        channelSpec0.putMipmap(2, new ImageAndMask("spec0-level2.png", null));

        final TileSpec tileSpec0 = new TileSpec();
        tileSpec0.addChannel(channelSpec0);

        List<TransformSpec> transformSpecList = new ArrayList<>();
        transformSpecList.add(new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D", "1 0 0 1 0 0"));

        tileSpec0.addTransformSpecs(transformSpecList);

        parameters.addTileSpec(tileSpec0);

        final ChannelSpec channelSpec1 = new ChannelSpec();
        channelSpec1.putMipmap(0, new ImageAndMask("spec1-level0.png", null));

        final TileSpec tileSpec1 = new TileSpec();
        tileSpec1.addChannel(channelSpec1);

        transformSpecList = new ArrayList<>();
        transformSpecList.add(new LeafTransformSpec("mpicbg.trakem2.transform.AffineModel2D", "1 0 0 1 1650 0"));

        tileSpec1.addTransformSpecs(transformSpecList);

        parameters.addTileSpec(tileSpec1);

        final Map<String, String> filterSpecParameters = new HashMap<>();
        filterSpecParameters.put("saturatedPixels", "99");
        final List<FilterSpec> filterSpecs =
                Collections.singletonList(new FilterSpec(EqualizeHistogram.class.getName(), filterSpecParameters)) ;
        parameters.setFilterSpecs(filterSpecs);

        final String json = parameters.toJson();

        assertNotNull(json, "json not generated");

        final RenderParameters parsedParameters = RenderParameters.parseJson(json);

        assertNotNull(parsedParameters, "json parse returned null parameters");
        assertEquals(width, parsedParameters.getWidth(), "invalid width parsed");

        final List<TileSpec> parsedTileSpecs = parsedParameters.getTileSpecs();
        assertNotNull(parsedTileSpecs, "json parse returned null tileSpecs");
        assertEquals(2, parsedTileSpecs.size(), "invalid number of tileSpecs parsed");

        assertFalse(parsedParameters.hasMipmapPathBuilder(), "mipmapPathBuilder should NOT be defined");

        assertTrue(parsedParameters.hasFilters(), "filter spec is missing");
    }

    @Test
    public void testMergeParameters() {

        final String overrideOut = "test-out.jpg";
        final String overrideScale = "0.3";
        final String[] args = {
                "--parameters_url", "src/test/resources/render-parameters-test/render.json",
                "--out", overrideOut,
                "--scale", overrideScale
        };
        final RenderParameters parameters = RenderParameters.parseCommandLineArgs(args);

        assertEquals(overrideOut, parameters.getOut(),
                     "invalid out parameter");
        assertEquals(overrideScale, String.valueOf(parameters.getScale()),
                     "invalid scale parameter");
        assertEquals("1.0", String.valueOf(parameters.getX()),
                     "x parameter not loaded from parameters_url file");
    }

    @Test
    public void testExtraneousComma() {
        final File jsonFile = new File("src/test/resources/render-parameters-test/extraneous-comma-render.json");
        assertThrows(IllegalArgumentException.class, () -> RenderParameters.parseJson(jsonFile));
    }

    @Test
    public void testIsRenderedCoordinateInsideTiles() {

        final RenderParameters renderParameters =
                RenderParameters.loadFromUrl("src/test/resources/render-parameters-test/rotated_bowtie_tiles.json");
        renderParameters.initializeDerivedValues();

        // derive bounding boxes for tiles since JSON is missing that data
        for (final TileSpec tileSpec : renderParameters.getTileSpecs()) {
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
        }

        final BufferedImage targetImage = renderParameters.openTargetImage();
//        ArgbRenderer.render(renderParameters, targetImage, ImageProcessorCache.DISABLED_CACHE);

        assertEquals(513, targetImage.getWidth(), "invalid target image width");
        assertEquals(353, targetImage.getHeight(), "invalid target image height");

        // scaled rendered result is 513x353
        //
        //      0 170 250 330 513
        //   0  o   o   o   o   o
        //  80  o   I   o   I   o
        // 180  o   I   I   I   o
        // 300  o   I   o   I   o
        // 353  o   o   o   o   o

        final int[] insideX = {170, 330, 170, 250, 330, 170, 330};
        final int[] insideY = { 80,  80, 180, 180, 180, 300, 300};

        for (int i = 0; i < insideX.length; i++) {
            final int x = insideX[i];
            final int y = insideY[i];
            assertTrue(renderParameters.isRenderedCoordinateInsideTiles(x, y),
                       "(" + x + ", " + y + ") should be inside tiles");
        }

        final int[] outsideX = {0, 170, 250, 330, 513,  0, 250, 510,   0, 510,   0, 250, 510,   0, 170, 250, 330, 510};
        final int[] outsideY = {0,   0,   0,   0,   0, 80,  80,  80, 180, 180, 300, 300, 300, 353, 353, 353, 353, 353};

        for (int i = 0; i < outsideX.length; i++) {
            final int x = outsideX[i];
            final int y = outsideY[i];
            assertFalse(renderParameters.isRenderedCoordinateInsideTiles(x, y),
                        "(" + x + ", " + y + ") should be outside tiles");
        }

        final Timer timer = new Timer();
        timer.start();
//        for (int y = 0; y < targetImage.getHeight(); y++) {
//            for (int x = 0; x < targetImage.getWidth(); x++) {
//                renderParameters.isRenderedCoordinateInsideTiles(x, y);
//            }
//        }
        final long elapsedMilliseconds = timer.stop();

//        System.out.println("checking 513x353 pixels took " + elapsedMilliseconds + " ms");

        assertTrue((elapsedMilliseconds < 20000),
                   "checking 513x353 pixels took too long (" + elapsedMilliseconds + " ms) to run");
    }

}
