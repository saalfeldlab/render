package org.janelia.alignment.util;

import java.awt.Color;
import java.util.ArrayList;
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
    public void testBuildColorList() throws Exception {

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
    public void testColorMappingConsistency() throws Exception {

        final int tileCount = 8000;
        final List<TileSpec> tileSpecs = new ArrayList<>(tileCount);
        TileSpec tileSpec;
        String imageUrl;
        for (int i = 0; i < tileCount; i++) {
            tileSpec = new TileSpec();
            imageUrl = "file://tile_" + i;
            final ChannelSpec channelSpec = new ChannelSpec();
            channelSpec.putMipmap(0, new ImageAndMask(imageUrl, null));
            tileSpec.addChannel(channelSpec);
            tileSpec.setWidth((double) i);
            tileSpec.setHeight((double) i + 1);
            tileSpecs.add(tileSpec);
        }

        final LabelImageProcessorCache cache1 = new LabelImageProcessorCache(100, false, false, tileSpecs);

        final LabelImageProcessorCache cache2 = new LabelImageProcessorCache(100, false, false, tileSpecs);

        for (int i = tileSpecs.size(); i > 0; i--) {
            tileSpec = tileSpecs.get(i-1);
            imageUrl = tileSpec.getFirstMipmapEntry().getValue().getImageUrl();
            Assert.assertEquals("different label color mapped for second cache instance",
                                cache1.getColorForUrl(imageUrl), cache2.getColorForUrl(imageUrl));
        }

    }

}
