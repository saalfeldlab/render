package org.janelia.render.service.util;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.ws.rs.core.Response;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.BoundingBoxRenderer;
import org.janelia.alignment.RenderParameters;
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
                                                                               true);
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
        return renderImageStream(renderParameters,
                                 Utils.PNG_FORMAT,
                                 IMAGE_PNG_MIME_TYPE,
                                 maxTileSpecsToRender,
                                 responseHelper);
    }

    public static Response renderTiffImage(final RenderParameters renderParameters,
                                           final Integer maxTileSpecsToRender,
                                           final ResponseHelper responseHelper) {
        return renderImageStream(renderParameters,
                                 Utils.TIFF_FORMAT,
                                 IMAGE_TIFF_MIME_TYPE,
                                 maxTileSpecsToRender,
                                 responseHelper);
    }

    public static Response renderImageStream(final RenderParameters renderParameters,
                                             final String format,
                                             final String mimeType,
                                             final Integer maxTileSpecsToRender,
                                             final ResponseHelper responseHelper) {

        LOG.info("renderImageStream: entry, format={}, mimeType={}", format, mimeType);

        Response response = null;
        try {

            // if we need to optimize render time (e.g. when we're rendering a box from a database stack)
            // and there are too many tiles to dynamically render the result quickly,
            // just render the tile bounding boxes instead ...
            final boolean renderBoundingBoxesOnly = (maxTileSpecsToRender != null) &&
                                                    (renderParameters.numberOfTileSpecs() > maxTileSpecsToRender);

            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters,
                                                                               renderBoundingBoxesOnly);
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
                                                                  final boolean renderBoundingBoxesOnly)
            throws IllegalArgumentException, IllegalStateException {

        LOG.info("validateParametersAndRenderImage: entry, renderParameters={}", renderParameters);

        renderParameters.initializeDerivedValues();
        renderParameters.validate();
        renderParameters.setNumberOfThreads(1); // service requests should always be single threaded

        final BufferedImage targetImage = renderParameters.openTargetImage();

        if (renderBoundingBoxesOnly) {

            final BoundingBoxRenderer boundingBoxRenderer = new BoundingBoxRenderer(renderParameters, Color.GREEN);
            boundingBoxRenderer.render(targetImage);

        } else {

            // otherwise render the real thing ...

            ArgbRenderer.render(renderParameters,
                                targetImage,
                                SharedImageProcessorCache.getInstance());

        }

        LOG.info("validateParametersAndRenderImage: exit");

        return targetImage;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderServiceUtil.class);
}