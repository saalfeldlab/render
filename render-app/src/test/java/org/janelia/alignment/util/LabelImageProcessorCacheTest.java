package org.janelia.alignment.util;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link LabelImageProcessorCache} class.
 *
 * @author Eric Trautman
 */
public class LabelImageProcessorCacheTest {

    @Test
    public void testBuildColors() throws Exception {

        final List<Integer> failedMaxLabelValues = new ArrayList<>();
        // make sure can generate correct number of labels for every tile count
        for (int maxLabels = 1; maxLabels < 8000; maxLabels++) {
            try {
                LabelImageProcessorCache.buildColorList(maxLabels);
            } catch (final IllegalStateException ise) {
                failedMaxLabelValues.add(maxLabels);
            }
        }

        Assert.assertEquals("could not create correct number of distinct colors for " + failedMaxLabelValues,
                            0, failedMaxLabelValues.size());

        final int maxLabels = 20000;
        final List<Color> colorList = LabelImageProcessorCache.buildColorList(maxLabels);

        Assert.assertEquals("invalid number of colors returned", maxLabels, colorList.size());

        final Map<Short, Color> shortToColorMap = new HashMap<>(colorList.size() * 2);
        short s;
        for (final Color c : colorList) {
            s = (short) c.getRGB();
            if (shortToColorMap.containsKey(s)) {
                Assert.fail(s + " maps to " + shortToColorMap.get(s) + " and " + c);
            } else {
                shortToColorMap.put(s, c);
            }
        }
    }

}
