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

import org.janelia.alignment.canvas.AlphaIntensityImageProcessors.AlphaIntensityChannel;

/**
 *
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class AlphaIntensityImageProcessors extends AbstractImageProcessorScaleLevel<AlphaIntensityChannel> {

    public enum AlphaIntensityChannel {
        ALPHA,
        INTENSITY
    }

    public AlphaIntensityImageProcessors(
            final ImageProcessor alpha,
            final ImageProcessor intensity,
            final int scaleLevelIndex) {

        super(scaleLevelIndex, alpha.getWidth(), alpha.getHeight());

        assert
            alpha.getWidth() == intensity.getWidth() &&
            alpha.getHeight() == intensity.getHeight() : "Channel dimensions do not match.";

        channels = new HashMap<AlphaIntensityChannel, ImageProcessor>();
        channels.put(AlphaIntensityChannel.ALPHA, alpha);
        channels.put(AlphaIntensityChannel.INTENSITY, intensity);
    }

    protected AlphaIntensityImageProcessors(
            final HashMap<AlphaIntensityChannel, ImageProcessor> channels,
            final int scaleLevelIndex,
            final int width,
            final int height) {

        super(scaleLevelIndex, width, height);

        this.channels = channels;
    }

    @Override
    public AlphaIntensityImageProcessors downsample() {
        return new AlphaIntensityImageProcessors(
                downsampleImageProcessors(),
                scaleLevelIndex + 1,
                ((int)max[ 0 ] + 1) / 2,
                ((int)max[ 1 ] + 1) / 2);
    }

    @Override
    public AlphaIntensityImageProcessors downsample(final int steps) {
        final int scale = 1 << steps;
        return new AlphaIntensityImageProcessors(
                downsampleImageProcessors(steps),
                scaleLevelIndex + steps,
                ((int)max[ 0 ] + 1) / scale,
                ((int)max[ 1 ] + 1) / scale);
    }
}
