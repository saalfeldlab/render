/*
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
package org.janelia.alignment;

import ij.process.FloatProcessor;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.util.LabelImageProcessorCache;

/**
 * Render a set of image tiles as a short (16-bit) label image.
 *
 * @author Eric Trautman
 */
public class LabelRenderer {

    /**
     * Constructs a renderer instance and renders to the specified image.
     *
     * @param  renderParameters          specifies what to render.
     * @param  targetImage               target for rendered result.
     * @param  labelImageProcessorCache  cache of source tile labels.
     *
     * @throws IllegalArgumentException
     *   if rendering fails for any reason.
     */
    public static void render(final RenderParameters renderParameters,
                              final BufferedImage targetImage,
                              final LabelImageProcessorCache labelImageProcessorCache)
            throws IllegalArgumentException {

        renderParameters.setBinaryMask(true);
        renderParameters.setMinIntensity(0.0);
        renderParameters.setMaxIntensity((double) LabelImageProcessorCache.MAX_LABEL_INTENSITY);

        Renderer.renderToBufferedImage(renderParameters, targetImage, labelImageProcessorCache, CONVERTER);
    }

    /**
     * Converts the processor to a short (16-bit) label image.
     *
     * @param  renderedImageProcessorWithMasks  processor to convert.
     *
     * @return the converted image.
     */
    private static BufferedImage targetToLabelImage(final ImageProcessorWithMasks renderedImageProcessorWithMasks) {

        // convert to 16-bit gray-scale
        if (! (renderedImageProcessorWithMasks.ip instanceof FloatProcessor)) {
            throw new IllegalArgumentException("target must be a " + FloatProcessor.class +
                                               " instance but instead is " +
                                               renderedImageProcessorWithMasks.ip.getClass());
        }

        final FloatProcessor fp = (FloatProcessor) renderedImageProcessorWithMasks.ip;
        final float[] pixels = (float[]) renderedImageProcessorWithMasks.ip.getPixels();
        final short[] labelPixels = new short[pixels.length];
        final short emptyPixel = (short) LabelImageProcessorCache.MAX_LABEL_INTENSITY;

        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == 0) {
                // map black to white for DMG
                labelPixels[i] = emptyPixel;
            } else {
                // simple cast is sufficient here because the label sources mapped into this target
                // are all 16-bit values with binary masking
                labelPixels[i] = (short) pixels[i];
            }
        }

        final BufferedImage image = new BufferedImage(fp.getWidth(), fp.getHeight(), BufferedImage.TYPE_USHORT_GRAY);
        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, fp.getWidth(), fp.getHeight(), labelPixels);

        return image;
    }

    private static final Renderer.ProcessorWithMasksConverter CONVERTER =
            (renderParameters, renderedImageProcessorWithMasks) -> targetToLabelImage(renderedImageProcessorWithMasks);
}
