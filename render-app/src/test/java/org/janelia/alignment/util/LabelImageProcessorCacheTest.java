package org.janelia.alignment.util;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link LabelImageProcessorCache} class.
 *
 * @author Eric Trautman
 */
public class LabelImageProcessorCacheTest {

    @Test
    public void testBuildColorList() {

        // ensure distinct set of 16-bit colors are generated
        final List<Color> colorList = LabelImageProcessorCache.buildColorList();

        final int maxLabels = 20000;
        final Map<Short, Color> shortToColorMap = new HashMap<>(maxLabels * 2);
        Color c;
        short s;
        for (int tileIndex = 0; tileIndex < maxLabels; tileIndex++) {
            c = colorList.get(tileIndex);
            s = (short) c.getRGB();
            if (shortToColorMap.containsKey(s)) {
                Assert.fail(s + " maps to " + shortToColorMap.get(s) + " and " + c);
            } else {
                shortToColorMap.put(s, c);
            }

//            // print css color style definitions for use in http://jsfiddle.net/trautmane/6wnasfxn/
//            if (tileIndex < 100) {
//                System.out.println(".color-" + tileIndex + " { background-color: rgb(" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "); }");
//            }

        }
    }

    @Test
    public void testColorMappingConsistency() {

        final int tileCount = 8000;
        final Map<String, TileSpec> tileSpecsA = new HashMap<>(tileCount * 2);
        final Map<String, TileSpec> tileSpecsB = new HashMap<>(tileCount * 2);
        TileSpec tileSpec;
        String imageUrl;
        for (int i = 0; i < tileCount; i++) {
            tileSpec = new TileSpec();
            tileSpec.setTileId(getTileId(i));
            imageUrl = "file://tile_" + i;
            final ChannelSpec channelSpec = new ChannelSpec();
            channelSpec.putMipmap(0, new ImageAndMask(imageUrl, null));
            tileSpec.addChannel(channelSpec);
            tileSpec.setWidth((double) i);
            tileSpec.setHeight((double) i + 1);
            tileSpecsA.put(tileSpec.getTileId(), tileSpec);
        }

        for (int i = tileCount - 1; i >= 0; i--) {
            tileSpec = tileSpecsA.get(getTileId(i));
            tileSpecsB.put(tileSpec.getTileId(), tileSpec);
        }

        final LabelImageProcessorCache cache1 = new LabelImageProcessorCache(100, false, false, tileSpecsA.values());

        final LabelImageProcessorCache cache2 = new LabelImageProcessorCache(100, false, false, tileSpecsB.values());

        for (int i = tileCount - 1; i >= 0; i--) {
            tileSpec = tileSpecsA.get(getTileId(i));
            imageUrl = tileSpec.getFirstMipmapEntry().getValue().getImageUrl();
            Assert.assertEquals("different label color mapped for second cache instance of " + getTileId(i),
                                cache1.getColorForUrl(imageUrl), cache2.getColorForUrl(imageUrl));
        }

    }

    private String getTileId(final int forIndex) {
        return "tile_" + forIndex;
    }
}
