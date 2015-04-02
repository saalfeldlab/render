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
package org.janelia.alignment.canvas;

import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.Map.Entry;

import mpicbg.trakem2.util.Downsampler;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
abstract public class AbstractImageProcessorScaleLevel<K> extends Abstract2DMultiChannelScaleLevel<K, ImageProcessor> {

    protected AbstractImageProcessorScaleLevel(
            final int scaleLevelIndex,
            final int width,
            final int height) {

        super();

        this.scaleLevelIndex = scaleLevelIndex;
        max[0] = width - 1;
        max[1] = height - 1;
    }

    protected AbstractImageProcessorScaleLevel(
            final HashMap<K, ImageProcessor> channels,
            final int scaleLevelIndex,
            final int width,
            final int height) {

        this(scaleLevelIndex, width, height);

        this.channels = channels;
    }

    protected HashMap<K, ImageProcessor> downsampleImageProcessors() {
        final HashMap<K, ImageProcessor> map = new HashMap<K, ImageProcessor>();
        for (final Entry<K, ImageProcessor> channelEntry : channels.entrySet()) {
            map.put(channelEntry.getKey(), Downsampler.downsampleImageProcessor(channelEntry.getValue()));
        }
        return map;
    }

    protected HashMap<K, ImageProcessor> downsampleImageProcessors(final int steps) {
        final HashMap<K, ImageProcessor> map = new HashMap<K, ImageProcessor>();
        for (final Entry<K, ImageProcessor> channelEntry : channels.entrySet()) {
            map.put(channelEntry.getKey(), Downsampler.downsampleImageProcessor(channelEntry.getValue(), steps));
        }
        return map;
    }
}
