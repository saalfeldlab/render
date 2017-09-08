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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Set;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.mipmap.AveragedChannelMipmapSource;
import org.janelia.alignment.mipmap.MipmapSource;
import org.janelia.alignment.mipmap.RenderedCanvasMipmapSource;
import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A renderer materializes a {@link RenderParameters} specification to memory or to disk.
 *
 * @author Stephan Saalfeld
 * @author Eric Trautman
 */
public class Renderer {

    public interface ImageOpener {
        /**
         * @return an empty target image with appropriate dimensions and type.
         */
        BufferedImage openTargetImage(final RenderParameters renderParameters);
    }

    public interface ProcessorWithMasksConverter {
        /**
         * @param  renderedImageProcessorWithMasks  the rendered result.
         *
         * @return an image converted from the specified rendered result.
         */
        BufferedImage convertProcessorWithMasksToImage(final RenderParameters renderParameters,
                                                       final ImageProcessorWithMasks renderedImageProcessorWithMasks);
    }

    private final RenderParameters renderParameters;
    private final ImageProcessorCache imageProcessorCache;

    /**
     * Creates a renderer instance.
     *
     * @param  renderParameters     specifies what to render.
     * @param  imageProcessorCache  cache of source tile data.
     */
    public Renderer(final RenderParameters renderParameters,
                    final ImageProcessorCache imageProcessorCache) {
        this.renderParameters = renderParameters;
        this.imageProcessorCache = imageProcessorCache;
    }

    /**
     * Validates this renderer's parameters, renders the image they specify, and saves the result to disk.
     *
     * @throws Exception
     *   if any part of the process fails.
     */
    public void validateRenderAndSaveImage(final ImageOpener imageOpener,
                                           final ProcessorWithMasksConverter converter)
            throws Exception {

        final long mainStart = System.currentTimeMillis();

        LOG.info("validateRenderAndSaveImage: entry, parameters={}", renderParameters);

        renderParameters.validate();

        final long openStart = System.currentTimeMillis();

        final BufferedImage targetImage = imageOpener.openTargetImage(renderParameters);

        final long renderStart = System.currentTimeMillis();
        renderToBufferedImage(converter, targetImage);

        final long saveStart = System.currentTimeMillis();

        final String outputPathOrUri = renderParameters.getOut();
        final String outputFormat = outputPathOrUri.substring(outputPathOrUri.lastIndexOf('.') + 1);

        Utils.saveImage(targetImage,
                        outputPathOrUri,
                        outputFormat,
                        renderParameters.isConvertToGray(),
                        renderParameters.getQuality());

        final long saveStop = System.currentTimeMillis();

        LOG.debug("validateRenderAndSaveImage: processing took {} milliseconds (open target: [}, render tiles:{}, save target:{})",
                  saveStop - mainStart,
                  renderStart - openStart,
                  saveStart - renderStart,
                  saveStop - saveStart);
    }

    /**
     * @return a rendered channel map.
     *
     * @throws IllegalArgumentException
     *   if rendering fails for any reason.
     */
    @SuppressWarnings("unused")
    public ChannelMap renderChannelMap()
            throws IllegalArgumentException {

        final ChannelMap canvasChannels;

        if (renderParameters.numberOfTileSpecs() > 0) {
            final RenderedCanvasMipmapSource renderedCanvasMipmapSource =
                    new RenderedCanvasMipmapSource(renderParameters, imageProcessorCache);
            canvasChannels = renderedCanvasMipmapSource.getChannels(0);
        } else {
            canvasChannels = new ChannelMap();
        }

        return canvasChannels;
    }

    /**
     * @return a rendered processor with masks.
     *         If multiple channels exist, they are averaged per the spec into a single channel.
     *
     * @throws IllegalArgumentException
     *   if rendering fails for any reason.
     */
    public ImageProcessorWithMasks renderImageProcessorWithMasks()
            throws IllegalArgumentException {

        ImageProcessorWithMasks worldTarget = null;

        if (renderParameters.numberOfTileSpecs() > 0) {
            final RenderedCanvasMipmapSource renderedCanvasMipmapSource =
                    new RenderedCanvasMipmapSource(renderParameters, imageProcessorCache);

            final MipmapSource canvas;
            final Set<String> channelNames = renderParameters.getChannelNames();
            final int numberOfTargetChannels = channelNames.size();
            if (numberOfTargetChannels > 1) {
                canvas = new AveragedChannelMipmapSource("averaged_canvas",
                                                         renderedCanvasMipmapSource,
                                                         renderParameters.getChannelNamesAndWeights());
            } else {
                canvas = renderedCanvasMipmapSource;
            }

            final ChannelMap canvasChannels = canvas.getChannels(0);
            if (canvasChannels.size() > 0) {
                worldTarget = canvasChannels.getFirstChannel();
            }
        }

        return worldTarget;
    }

    /**
     * Renders to the specified image.
     *
     * @param  converter    converts to the desired output type.
     * @param  targetImage  target for rendered result.
     *
     * @throws IllegalArgumentException
     *   if rendering fails for any reason.
     */
    public void renderToBufferedImage(final ProcessorWithMasksConverter converter,
                                      final BufferedImage targetImage)
            throws IllegalArgumentException {

        final int numberOfTileSpecs = renderParameters.numberOfTileSpecs();

        LOG.debug("renderToBufferedImage: entry, processing {} tile specifications, numberOfThreads={}",
                  numberOfTileSpecs, renderParameters.getNumberOfThreads());

        final long tileLoopStart = System.currentTimeMillis();

        final ImageProcessorWithMasks worldTarget = renderImageProcessorWithMasks();

        final long drawImageStart = System.currentTimeMillis();

        if (worldTarget != null) {

            final Graphics2D targetGraphics = targetImage.createGraphics();

            // TODO: see if there is a more efficient way to do the background fill and avoid redraw of image below
            final Integer backgroundRGBColor = renderParameters.getBackgroundRGBColor();
            if (backgroundRGBColor != null) {
                targetGraphics.setBackground(new Color(backgroundRGBColor));
                targetGraphics.clearRect(0, 0, targetImage.getWidth(), targetImage.getHeight());
            }

            final BufferedImage image = converter.convertProcessorWithMasksToImage(renderParameters, worldTarget);
            targetGraphics.drawImage(image, 0, 0, null);

            targetGraphics.dispose();

        }

        final long drawImageStop = System.currentTimeMillis();

        LOG.debug("renderToBufferedImage: exit, {} tiles processed in {} milliseconds, draw image:{}",
                  numberOfTileSpecs,
                  System.currentTimeMillis() - tileLoopStart,
                  drawImageStop - drawImageStart);
    }

    /**
     * Constructs a renderer instance and renders to the specified image.
     *
     * @param  renderParameters     specifies what to render.
     * @param  targetImage          target for rendered result.
     * @param  imageProcessorCache  cache of source tile data.
     * @param  converter            converts to the desired output type.
     *
     * @throws IllegalArgumentException
     *   if rendering fails for any reason.
     */
    public static void renderToBufferedImage(final RenderParameters renderParameters,
                                             final BufferedImage targetImage,
                                             final ImageProcessorCache imageProcessorCache,
                                             final ProcessorWithMasksConverter converter)
            throws IllegalArgumentException {
        final Renderer renderer = new Renderer(renderParameters, imageProcessorCache);
        renderer.renderToBufferedImage(converter, targetImage);
    }

    /**
     * Constructs a renderer instance and saves the rendered result to disk.
     *
     * @param  args         command line arguments for constructing a {@link RenderParameters} instance.
     * @param  imageOpener  creates an empty target image with appropriate dimensions and type.
     * @param  converter    converts to the desired output type.
     *
     * @throws Exception
     *   if rendering fails for any reason.
     */
    public static void renderUsingCommandLineArguments(final String[] args,
                                                       final ImageOpener imageOpener,
                                                       final ProcessorWithMasksConverter converter)
            throws Exception {
        final RenderParameters renderParameters = RenderParameters.parseCommandLineArgs(args);
        if (renderParameters.displayHelp()) {
            renderParameters.showUsage();
        } else {
            final Renderer renderer = new Renderer(renderParameters, new ImageProcessorCache());
            renderer.validateRenderAndSaveImage(imageOpener, converter);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(Renderer.class);
}
