package org.janelia.render.service.util;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.ws.rs.core.Response;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.BoundingBoxRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.ShortRenderer;
import org.janelia.alignment.Utils;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utility methods and constants for Render services.
 *
 * @author Eric Trautman
 */
public class RenderServiceUtil {

    public static final String IMAGE_JPEG_MIME_TYPE = "image/jpeg";
    public static final String IMAGE_PNG_MIME_TYPE = "image/png";
    public static final String IMAGE_TIFF_MIME_TYPE = "image/tiff";
    public static final String IMAGE_RAW_MIME_TYPE = "application/octet-stream";

    public static void throwServiceException(final Throwable t)
            throws ServiceException {

        LOG.error("service failure", t);

        if (t instanceof ServiceException) {
            throw (ServiceException) t;
        } else if (t instanceof IllegalArgumentException) {
            throw new IllegalServiceArgumentException(t.getMessage(), t);
        } else {
            throw new ServiceException(t.getMessage(), t);
        }
    }

    public static Response renderJpegBoundingBoxes(final RenderParameters renderParameters,
                                                   final ResponseHelper responseHelper) {

        LOG.info("renderJpegBoundingBoxes: entry");

        Response response = null;
        try {

            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters,
                                                                               true,
                                                                               false);
            final BufferedImageStreamingOutput out =
                    new BufferedImageStreamingOutput(targetImage,
                                                     Utils.JPEG_FORMAT,
                                                     renderParameters.isConvertToGray(),
                                                     renderParameters.getQuality());
            response = responseHelper.getImageByteResponse(out, IMAGE_JPEG_MIME_TYPE);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        LOG.info("renderJpegBoundingBoxes: exit");

        return response;
    }

    public static Response renderJpegImage(final RenderParameters renderParameters,
                                           final Integer maxTileSpecsToRender,
                                           final ResponseHelper responseHelper) {
        return renderImageStream(renderParameters,
                                 Utils.JPEG_FORMAT,
                                 IMAGE_JPEG_MIME_TYPE,
                                 maxTileSpecsToRender,
                                 responseHelper);
    }


    public static Response renderPngImage(final RenderParameters renderParameters,
                                          final Integer maxTileSpecsToRender,
                                          final ResponseHelper responseHelper) {
        return renderPngImage(renderParameters, maxTileSpecsToRender, responseHelper, false);
    }

    public static Response renderPngImage(final RenderParameters renderParameters,
                                          final Integer maxTileSpecsToRender,
                                          final ResponseHelper responseHelper,
                                          final boolean render16bit) {
        return renderImageStream(renderParameters,
                                 Utils.PNG_FORMAT,
                                 IMAGE_PNG_MIME_TYPE,
                                 maxTileSpecsToRender,
                                 responseHelper,
                                 render16bit);
    }

    public static Response renderRawImage(final RenderParameters renderParameters,
                                          final Integer maxTileSpecsToRender,
                                          final ResponseHelper responseHelper) {
        return renderRawImage(renderParameters, maxTileSpecsToRender, responseHelper, false);

    }

    public static Response renderRawImage(final RenderParameters renderParameters,
                                          final Integer maxTileSpecsToRender,
                                          final ResponseHelper responseHelper,
                                          final boolean render16bit) {
        return renderImageStream(renderParameters,
                                 Utils.RAW_FORMAT,
                                 IMAGE_RAW_MIME_TYPE,
                                 maxTileSpecsToRender,
                                 responseHelper,
                                 render16bit);
    }

    public static Response renderTiffImage(final RenderParameters renderParameters,
                                           final Integer maxTileSpecsToRender,
                                           final ResponseHelper responseHelper) {
        return renderTiffImage(renderParameters, maxTileSpecsToRender, responseHelper, false);
    }

    public static Response renderTiffImage(final RenderParameters renderParameters,
                                           final Integer maxTileSpecsToRender,
                                           final ResponseHelper responseHelper,
                                           final boolean render16bit) {
        return renderImageStream(renderParameters,
                                 Utils.TIFF_FORMAT,
                                 IMAGE_TIFF_MIME_TYPE,
                                 maxTileSpecsToRender,
                                 responseHelper,
                                 render16bit);
    }

    public static Response renderImageStream(final RenderParameters renderParameters,
                                             final String format,
                                             final String mimeType,
                                             final Integer maxTileSpecsToRender,
                                             final ResponseHelper responseHelper) {
        return renderImageStream(renderParameters, format, mimeType, maxTileSpecsToRender, responseHelper, false);
    }

    private static Response renderImageStream(final RenderParameters renderParameters,
                                              final String format,
                                              final String mimeType,
                                              final Integer maxTileSpecsToRender,
                                              final ResponseHelper responseHelper,
                                              final boolean render16bit) {

        LOG.info("renderImageStream: entry, format={}, mimeType={}", format, mimeType);

        Response response = null;
        try {

            // if we need to optimize render time (e.g. when we're rendering a box from a database stack)
            // and there are too many tiles to dynamically render the result quickly,
            // just render the tile bounding boxes instead ...
            Integer maxTilesToRender = maxTileSpecsToRender;
            if (maxTileSpecsToRender == null) {
                maxTilesToRender = RenderServerProperties.getProperties().getInteger("webService.maxTileSpecsToRender");
            }

            boolean renderBoundingBoxesOnly = (maxTilesToRender != null) &&
                                              (renderParameters.numberOfTileSpecs() > maxTilesToRender);

            // TODO: replace this hack with a proper debugWarpField parameter
            if ((maxTileSpecsToRender != null) && (maxTileSpecsToRender < 0)) {
                final Integer defaultMaxTileSpecsToRender =
                        RenderServerProperties.getProperties().getInteger("webService.maxTileSpecsToRender");
                renderBoundingBoxesOnly = (defaultMaxTileSpecsToRender != null) &&
                                          (renderParameters.numberOfTileSpecs() > defaultMaxTileSpecsToRender);
                if (renderParameters.numberOfTileSpecs() < 40) {
                    renderParameters.setAddWarpFieldDebugOverlay(true);
                }
            }

            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters,
                                                                               renderBoundingBoxesOnly,
                                                                               render16bit);
            final BufferedImageStreamingOutput out =
                    new BufferedImageStreamingOutput(targetImage,
                                                     format,
                                                     renderParameters.isConvertToGray(),
                                                     renderParameters.getQuality());
            response = responseHelper.getImageByteResponse(out, mimeType);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        LOG.info("renderImageStream: exit");

        return response;
    }

    public static Response streamImageFile(final File imageFile,
                                           final String mimeType,
                                           final ResponseHelper responseHelper) {

        LOG.info("streamImageFile: entry, imageFile={}", imageFile);

        Response response = null;
        try {

            final FileStreamingOutput out = new FileStreamingOutput(imageFile);
            response = responseHelper.getImageByteResponse(out, mimeType);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        LOG.info("streamImageFile: exit");

        return response;
    }

    private static BufferedImage validateParametersAndRenderImage(final RenderParameters renderParameters,
                                                                  final boolean renderBoundingBoxesOnly,
                                                                  final boolean render16bit)
            throws IllegalArgumentException, IllegalStateException {

        LOG.info("validateParametersAndRenderImage: entry, renderParameters={}", renderParameters);

        renderParameters.initializeDerivedValues();

        // only validate source images and masks if we are rendering real data
        if (!renderBoundingBoxesOnly) {
            renderParameters.validate();
        }

        renderParameters.setNumberOfThreads(1); // service requests should always be single threaded

        final BufferedImage targetImage;

        if (renderBoundingBoxesOnly) {
            targetImage = renderParameters.openTargetImage();
            final BoundingBoxRenderer boundingBoxRenderer = new BoundingBoxRenderer(renderParameters, Color.GREEN);
            boundingBoxRenderer.render(targetImage);

        } else {

            // otherwise render the real thing ...
            if (render16bit) {
                targetImage = renderParameters.openTargetImage(BufferedImage.TYPE_USHORT_GRAY);
                ShortRenderer.render(renderParameters,
                                     targetImage,
                                     SharedImageProcessorCache.getInstance());
            } else {
                targetImage = renderParameters.openTargetImage();
                ArgbRenderer.render(renderParameters,
                                    targetImage,
                                    SharedImageProcessorCache.getInstance());
            }

        }

        LOG.info("validateParametersAndRenderImage: exit");

        return targetImage;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderServiceUtil.class);
}