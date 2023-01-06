package org.janelia.alignment;

import ij.process.ByteProcessor;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.util.ImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render a set of image tiles as a byte (8-bit) image.
 *
 * @author Eric Trautman
 */
public class ByteRenderer {

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
     * Converts the processor to a byte (8-bit) image.
     *
     * @param  renderedImageProcessorWithMasks  processor to convert.
     *
     * @return the converted image.
     */
    public static BufferedImage targetToByteImage(final ImageProcessorWithMasks renderedImageProcessorWithMasks) {
        // convert to 8-bit gray-scale
        final ByteProcessor bp = renderedImageProcessorWithMasks.ip.convertToByteProcessor();

        final BufferedImage image = new BufferedImage(bp.getWidth(), bp.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        final WritableRaster raster = image.getRaster();
        raster.setDataElements(0, 0, bp.getWidth(), bp.getHeight(), bp.getPixels());

        return image;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ByteRenderer.class);

    private static final Renderer.ImageOpener OPENER = (renderParameters) -> {
        final double derivedScale = renderParameters.getScale();
        final int targetWidth = (int) (derivedScale * renderParameters.getWidth());
        final int targetHeight = (int) (derivedScale * renderParameters.getHeight());
        return new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
    };

    public static final Renderer.ProcessorWithMasksConverter CONVERTER =
            (renderParameters, renderedImageProcessorWithMasks) -> targetToByteImage(renderedImageProcessorWithMasks);
}
