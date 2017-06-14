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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.List;
import java.util.Set;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.mipmap.AveragedChannelMipmapSource;
import org.janelia.alignment.mipmap.MipmapSource;
import org.janelia.alignment.mipmap.RenderedCanvasMipmapSource;
import org.janelia.alignment.spec.TileSpec;
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

    private static final Logger LOG = LoggerFactory.getLogger(ArgbRenderer.class);

    private ArgbRenderer() {
    }

    public static void render(final RenderParameters params,
                              final BufferedImage targetImage,
                              final ImageProcessorCache imageProcessorCache)
            throws IllegalArgumentException {

        final long tileLoopStart = System.currentTimeMillis();

        final List<TileSpec> tileSpecs = params.getTileSpecs();

        LOG.debug("render: entry, processing {} tile specifications, numberOfThreads={}",
                  tileSpecs.size(), params.getNumberOfThreads());

        final int targetWidth = targetImage.getWidth();
        final int targetHeight = targetImage.getHeight();

        final RenderedCanvasMipmapSource renderedCanvasMipmapSource =
                new RenderedCanvasMipmapSource(params, imageProcessorCache);

        final MipmapSource canvas;
        final Set<String> channelNames = params.getChannelNames();
        final int numberOfTargetChannels = channelNames.size();
        if (numberOfTargetChannels > 1) {
            canvas = new AveragedChannelMipmapSource("averaged_canvas",
                                                     renderedCanvasMipmapSource,
                                                     params.getChannelNamesAndWeights());
        }  else {
            canvas = renderedCanvasMipmapSource;
        }

        final ChannelMap canvasChannels = canvas.getChannels(0);

        final long drawImageStart = System.currentTimeMillis();

        final Graphics2D targetGraphics = targetImage.createGraphics();

        final Integer backgroundRGBColor = params.getBackgroundRGBColor();
        if (backgroundRGBColor != null) {
            targetGraphics.setBackground(new Color(backgroundRGBColor));
            targetGraphics.clearRect(0, 0, targetWidth, targetHeight);
        }

        if ((tileSpecs.size() > 0) && (canvasChannels.size() > 0)) {

            final ImageProcessorWithMasks worldTarget = canvasChannels.getFirstChannel();
            final BufferedImage image = targetToARGBImage(worldTarget,
                                                          params.binaryMask());
            targetGraphics.drawImage(image, 0, 0, null);
        }

        targetGraphics.dispose();

        final long drawImageStop = System.currentTimeMillis();

        LOG.debug("render: exit, {} tiles processed in {} milliseconds, draw image:{}",
                  tileSpecs.size(),
                  System.currentTimeMillis() - tileLoopStart,
                  drawImageStop - drawImageStart);

    }

    public static BufferedImage targetToARGBImage(final ImageProcessorWithMasks target,
                                                  final boolean binaryMask) {

        // convert to 24bit RGB
        final ColorProcessor cp = target.ip.convertToColorProcessor();

        // set alpha channel
        final int[] cpPixels = (int[]) cp.getPixels();
        final byte[] alphaPixels;

        if (target.mask != null) {
            alphaPixels = (byte[]) target.mask.getPixels();
        } else if (target.outside != null) {
            alphaPixels = (byte[]) target.outside.getPixels();
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

    public static BufferedImage renderWithNoise(final RenderParameters renderParameters,
                                                final boolean fillWithNoise) {

        LOG.info("renderWithNoise: entry, fillWithNoise={}", fillWithNoise);

        final BufferedImage bufferedImage = renderParameters.openTargetImage();
        final ByteProcessor ip = new ByteProcessor(bufferedImage.getWidth(), bufferedImage.getHeight());

        if (fillWithNoise) {
            mpicbg.ij.util.Util.fillWithNoise(ip);
            bufferedImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
        }

        ArgbRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

        LOG.info("renderWithNoise: exit");

        return bufferedImage;
    }

    public static void renderUsingCommandLineArguments(final String[] args)
            throws Exception {

        final long mainStart = System.currentTimeMillis();
        long parseStop = mainStart;
        long targetOpenStop = mainStart;
        long saveStart = mainStart;
        long saveStop = mainStart;

        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);

        if (params.displayHelp()) {

            params.showUsage();

        } else {

            LOG.info("renderUsingCommandLineArguments: entry, params={}", params);

            params.validate();

            parseStop = System.currentTimeMillis();

            final BufferedImage targetImage = params.openTargetImage();

            targetOpenStop = System.currentTimeMillis();

            final ImageProcessorCache imageProcessorCache = new ImageProcessorCache();
            render(params,
                   targetImage,
                   imageProcessorCache);

            saveStart = System.currentTimeMillis();

            // save the modified image
            final String outputPathOrUri = params.getOut();
            final String outputFormat = outputPathOrUri.substring(outputPathOrUri.lastIndexOf('.') + 1);
            Utils.saveImage(targetImage,
                            outputPathOrUri,
                            outputFormat,
                            params.isConvertToGray(),
                            params.getQuality());

            saveStop = System.currentTimeMillis();
        }

        LOG.debug("renderUsingCommandLineArguments: processing took {} milliseconds (parse command:{}, open target:{}, render tiles:{}, save target:{})",
                  saveStop - mainStart,
                  parseStop - mainStart,
                  targetOpenStop - parseStop,
                  saveStart - targetOpenStop,
                  saveStop - saveStart);

    }

    public static void main(final String[] args) {

        try {
            renderUsingCommandLineArguments(args);
        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }

    }

}
