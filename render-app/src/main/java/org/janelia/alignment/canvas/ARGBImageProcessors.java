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

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.util.HashMap;

import org.janelia.alignment.canvas.ARGBImageProcessors.ARGBChannel;

/**
 * Four {@link ImageProcessor ImageProcessors}, one for each channel in an ARGB
 * image.  Alpha's min and max values specify the range [0, 1.0].  For all other
 * channels, they may be used for mapping into an arbitrary target range.
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class ARGBImageProcessors extends AbstractImageProcessorScaleLevel<ARGBChannel> {

    public enum ARGBChannel {
        ALPHA,
        RED,
        GREEN,
        BLUE
    }

    public ARGBImageProcessors(
            final ImageProcessor alpha,
            final ImageProcessor red,
            final ImageProcessor green,
            final ImageProcessor blue,
            final int scaleLevelIndex) {

        super(scaleLevelIndex, alpha.getWidth(), alpha.getHeight());

        assert
            alpha.getWidth() == red.getWidth() &&
            red.getWidth() == green.getWidth() &&
            green.getWidth() == blue.getWidth() &&
            alpha.getHeight() == red.getHeight() &&
            red.getHeight() == green.getHeight() &&
            green.getHeight() == blue.getHeight() : "Channel dimensions do not match.";

        channels = new HashMap<ARGBChannel, ImageProcessor>();
        channels.put(ARGBChannel.ALPHA, alpha);
        channels.put(ARGBChannel.RED, red);
        channels.put(ARGBChannel.GREEN, green);
        channels.put(ARGBChannel.BLUE, blue);
    }

    /**
     * Creates an {@link ARGBImageProcessors} from a {@link ColorProcessor},
     * copying and separating all channels.
     *
     * @param argb
     * @param scaleLevelIndex
     */
    public ARGBImageProcessors(
            final ColorProcessor argb,
            final int scaleLevelIndex) {

        super(scaleLevelIndex, argb.getWidth(), argb.getHeight());

        final ByteProcessor alpha = new ByteProcessor(argb.getWidth(), argb.getHeight(), argb.getChannel(4));
        alpha.setMinAndMax(0, 255);
        final ByteProcessor red = new ByteProcessor(argb.getWidth(), argb.getHeight(), argb.getChannel(1));
        red.setMinAndMax(argb.getMin(), argb.getMax());
        final ByteProcessor green = new ByteProcessor(argb.getWidth(), argb.getHeight(), argb.getChannel(2));
        green.setMinAndMax(argb.getMin(), argb.getMax());
        final ByteProcessor blue = new ByteProcessor(argb.getWidth(), argb.getHeight(), argb.getChannel(3));
        blue.setMinAndMax(argb.getMin(), argb.getMax());

        channels = new HashMap<ARGBChannel, ImageProcessor>();
        channels.put(ARGBChannel.ALPHA, alpha);
        channels.put(ARGBChannel.RED, red);
        channels.put(ARGBChannel.GREEN, green);
        channels.put(ARGBChannel.BLUE, blue);
    }

    protected ARGBImageProcessors(
            final HashMap<ARGBChannel, ImageProcessor> channels,
            final int scaleLevelIndex,
            final int width,
            final int height) {

        super(scaleLevelIndex, width, height);

        this.channels = channels;
    }

    @Override
    public ARGBImageProcessors downsample() {
        return new ARGBImageProcessors(
                downsampleImageProcessors(),
                scaleLevelIndex + 1,
                ((int)max[ 0 ] + 1) / 2,
                ((int)max[ 1 ] + 1) / 2);
    }

    @Override
    public ARGBImageProcessors downsample(final int steps) {
        final int scale = 1 << steps;
        return new ARGBImageProcessors(
                downsampleImageProcessors(steps),
                scaleLevelIndex + steps,
                ((int)max[ 0 ] + 1) / scale,
                ((int)max[ 1 ] + 1) / scale);
    }
}
