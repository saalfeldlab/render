/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.mipmap;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.ChannelMap;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link AveragedChannelMipmapSource} class.
 *
 * @author Eric Trautman
 */
public class AveragedChannelMipmapSourceTest {

    private final String firstChannelName = "DAPI";
    private final String secondChannelName = "TdTomato";

    @Test
    public void testGetChannels() throws Exception {


        final String[] args = {
                "--tile_spec_url", "src/test/resources/multichannel-test/test_2_channels.json",
                "--channel", firstChannelName,
                "--channel", secondChannelName,
                "--out", "not-applicable-but-required-file-name.png",
                "--x", "650",
                "--y", "1600",
                "--width", "4000",
                "--height", "2200",
                "--scale", "0.25"
        };

        final RenderParameters renderParameters = RenderParameters.parseCommandLineArgs(args);

        final RenderedCanvasMipmapSource renderedCanvasMipmapSource =
                new RenderedCanvasMipmapSource(renderParameters,
                                               ImageProcessorCache.DISABLED_CACHE);

        final ImageProcessorWithMasks onlySecondChannel =
                getAveragedChannel(secondChannelName, renderedCanvasMipmapSource, 0.0, 1.0);

        final ImageProcessorWithMasks averagedChannel =
                getAveragedChannel("averaged_75_25", renderedCanvasMipmapSource, 0.75, 0.25);

        final int pixelIndex = 100000;
        Assert.assertNotSame("channel intensity should not match",
                             onlySecondChannel.ip.getf(pixelIndex), averagedChannel.ip.getf(pixelIndex));

        final BufferedImage image = ArgbRenderer.targetToARGBImage(averagedChannel, 0.0, 10000.0, false, null, null);

        Assert.assertNotNull("averaged image not rendered", image);
    }

    private ImageProcessorWithMasks getAveragedChannel(final String convertedChannelName,
                                                       final MipmapSource source,
                                                       final double firstChannelWeight,
                                                       final double secondChannelWeight) {

        final Map<String, Double> channelToWeightMap = new HashMap<>();
        channelToWeightMap.put(firstChannelName, firstChannelWeight);
        channelToWeightMap.put(secondChannelName, secondChannelWeight);

        final AveragedChannelMipmapSource convertedSource = new AveragedChannelMipmapSource(convertedChannelName,
                                                                                            source,
                                                                                            channelToWeightMap);

        final ChannelMap channelMap = convertedSource.getChannels(0);
        return channelMap.get(convertedChannelName);
    }

}
