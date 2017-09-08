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
package org.janelia.alignment;

import ij.process.ByteProcessor;
import ij.process.ColorProcessor;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render a set of image tiles as an ARGB image.
 * <p/>
 * <pre>
 * Usage: java [-options] -cp render.jar org.janelia.alignment.ArgbRenderer [options]
 * Options:
 *       --height
 *      Target image height
 *      Default: 256
 *       --help
 *      Display this note
 *      Default: false
 * *     --res
 *      Mesh resolution, specified by the desired size of a triangle in pixels
 *       --in
 *      Path to the input image if any
 *       --out
 *      Path to the output image
 * *     --tile_spec_url
 *      URL to JSON tile spec
 *       --width
 *      Target image width
 *      Default: 256
 * *     --x
 *      Target image left coordinate
 *      Default: 0
 * *     --y
 *      Target image top coordinate
 *      Default: 0
 * </pre>
 * <p>E.g.:</p>
 * <pre>java -cp render.jar org.janelia.alignment.ArgbRenderer \
 *   --tile_spec_url "file://absolute/path/to/tile-spec.json" \
 *   --out "/absolute/path/to/output.png" \
 *   --x 16536
 *   --y 32
 *   --width 1024
 *   --height 1024
 *   --res 64</pre>
 *
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 */
public class ArgbRenderer {

    /**
     * Constructs a renderer instance and renders to the specified image.
     *
     * @param  renderParameters     specifies what to render.
     * @param  targetImage          target for rendered result.
     * @param  imageProcessorCache  cache of source tile data.
     *
     * @throws IllegalArgumentException
     *   if rendering fails for any reason.
     */
    public static void render(final RenderParameters renderParameters,
                              final BufferedImage targetImage,
                              final ImageProcessorCache imageProcessorCache)
            throws IllegalArgumentException {
        Renderer.renderToBufferedImage(renderParameters, targetImage, imageProcessorCache, CONVERTER);
    }

    /**
     * Constructs a renderer instance and renders an image optionally pre-filled with noise.
     *
     * @param  renderParameters  specifies what to render.
     * @param  fillWithNoise     indicates whether image should be filled with noise before rendering.
     *
     * @return the rendered image.
     */
    public static BufferedImage renderWithNoise(final RenderParameters renderParameters,
                                                final boolean fillWithNoise) {

        LOG.info("renderWithNoise: entry, fillWithNoise={}", fillWithNoise);

        final BufferedImage bufferedImage = renderParameters.openTargetImage();

        if (fillWithNoise) {
            final ByteProcessor ip = new ByteProcessor(bufferedImage.getWidth(), bufferedImage.getHeight());
            mpicbg.ij.util.Util.fillWithNoise(ip);
            bufferedImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
        }

        ArgbRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        LOG.info("renderWithNoise: exit");

        return bufferedImage;
    }

    /**
     * Constructs a renderer instance and saves the rendered result to disk.
     * This is basically the 'main' method but it has been extracted so that it can be more easily used for tests.
     *
     * @param  args  command line arguments for constructing a {@link RenderParameters} instance.
     *
     * @throws Exception
     *   if rendering fails for any reason.
     */
    public static void renderUsingCommandLineArguments(final String[] args)
            throws Exception {
        Renderer.renderUsingCommandLineArguments(args, OPENER, CONVERTER);
    }

    public static void main(final String[] args) {

        try {
            renderUsingCommandLineArguments(args);
        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }

    }

    /**
     * Converts the processor to an ARGB image.
     *
     * @param  renderedImageProcessorWithMasks  processor to convert.
     * @param  binaryMask                       indicates whether a binary mask should be applied.
     *
     * @return the converted image.
     */
    public static BufferedImage targetToARGBImage(final ImageProcessorWithMasks renderedImageProcessorWithMasks,
                                                  final boolean binaryMask) {

        // convert to 24bit RGB
        final ColorProcessor cp = renderedImageProcessorWithMasks.ip.convertToColorProcessor();

        // set alpha channel
        final int[] cpPixels = (int[]) cp.getPixels();
        final byte[] alphaPixels;

        if (renderedImageProcessorWithMasks.mask != null) {
            alphaPixels = (byte[]) renderedImageProcessorWithMasks.mask.getPixels();
        } else if (renderedImageProcessorWithMasks.outside != null) {
            alphaPixels = (byte[]) renderedImageProcessorWithMasks.outside.getPixels();
        } else {
            alphaPixels = null;
        }

        if (alphaPixels == null) {
            for (int i = 0; i < cpPixels.length; ++i) {
                cpPixels[i] &= 0xffffffff;
            }
        } else if (binaryMask) {
            for (int i = 0; i < cpPixels.length; ++i) {
                if (alphaPixels[i] == -1)
                    cpPixels[i] &= 0xffffffff;
                else
                    cpPixels[i] &= 0x00ffffff;
            }
        } else {
            for (int i = 0; i < cpPixels.length; ++i) {
                cpPixels[i] &= 0x00ffffff | (alphaPixels[i] << 24);
            }
        }

        final BufferedImage image = new BufferedImage(cp.getWidth(), cp.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, cp.getWidth(), cp.getHeight(), cpPixels);

        return image;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ArgbRenderer.class);

    private static final Renderer.ImageOpener OPENER = RenderParameters::openTargetImage;

    private static final Renderer.ProcessorWithMasksConverter CONVERTER =
            (renderParameters, renderedImageProcessorWithMasks) -> targetToARGBImage(renderedImageProcessorWithMasks,
                                                                                     renderParameters.binaryMask());
}
