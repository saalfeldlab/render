package org.janelia.render.service;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * RESTful web service API for {@link Render} tool.
 *
 * @author Eric Trautman
 */
@Path("/v1/project")
public class RenderService {

    private RenderParametersDao renderParametersDao;

    public RenderService() {
        this.renderParametersDao = new RenderParametersDao(new File("/groups/flyTEM/flyTEM/trautmane/parameters"));
    }

    @Path("{projectId}/stack/{stackId}/box/{x},{y},{width},{height}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RenderParameters getRenderParameters(@PathParam("projectId") String projectId,
                                                @PathParam("stackId") String stackId,
                                                @PathParam("x") Integer x,
                                                @PathParam("y") Integer y,
                                                @PathParam("width") Integer width,
                                                @PathParam("height") Integer height) {

        LOG.info("getRenderParameters: entry, projectId={}, stackId={}, x={}, y={}, width={}, height={}",
                 projectId, stackId, x, y, width, height);

        RenderParameters parameters = null;
        try {
            parameters = renderParametersDao.getParameters(projectId, stackId, x, y, width, height);
        } catch (Throwable t) {
            throwServiceException(t);
        }
        return parameters;
    }

    @Path("{projectId}/stack/{stackId}/box/{x},{y},{width},{height}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImageForBox(@PathParam("projectId") String projectId,
                                          @PathParam("stackId") String stackId,
                                          @PathParam("x") Integer x,
                                          @PathParam("y") Integer y,
                                          @PathParam("width") Integer width,
                                          @PathParam("height") Integer height) {

        LOG.info("renderJpegImageForBox: entry");
        final RenderParameters renderParameters = getRenderParameters(projectId, stackId, x, y, width, height);
        return renderJpegImage(projectId, renderParameters);
    }

    @Path("{projectId}/stack/{stackId}/box/{x},{y},{width},{height}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    public Response renderPngImageForBox(@PathParam("projectId") String projectId,
                                         @PathParam("stackId") String stackId,
                                         @PathParam("x") Integer x,
                                         @PathParam("y") Integer y,
                                         @PathParam("width") Integer width,
                                         @PathParam("height") Integer height) {

        LOG.info("renderPngImageForBox: entry");
        final RenderParameters renderParameters = getRenderParameters(projectId, stackId, x, y, width, height);
        return renderPngImage(projectId, renderParameters);
    }

    @Path("{projectId}/jpeg-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImage(@PathParam("projectId") String projectId,
                                    RenderParameters renderParameters) {
        LOG.info("renderJpegImage: entry, projectId={}", projectId);
        return renderImageStream(renderParameters, Utils.JPEG_FORMAT, IMAGE_JPEG_MIME_TYPE);
    }


    @Path("{projectId}/png-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderPngImage(@PathParam("projectId") String projectId,
                                    RenderParameters renderParameters) {
        LOG.info("renderPngImage: entry, projectId={}", projectId);
        return renderImageStream(renderParameters, Utils.PNG_FORMAT, IMAGE_PNG_MIME_TYPE);
    }

    @Path("{projectId}/jpeg-file")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response renderJpegFile(@PathParam("projectId") String projectId,
                                   RenderParameters renderParameters) {

        LOG.info("renderJpegFile: entry, projectId={}", projectId);
        return renderImageFile(renderParameters, Utils.JPEG_FORMAT);
    }

    @Path("{projectId}/png-file")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response renderPngFile(@PathParam("projectId") String projectId,
                                  RenderParameters renderParameters) {

        LOG.info("renderPngFile: entry, projectId={}", projectId);
        return renderImageFile(renderParameters, Utils.PNG_FORMAT);
    }

    private Response renderImageStream(RenderParameters renderParameters,
                                       String format,
                                       String mimeType) {

        LOG.info("renderImageStream: entry, format={}, mimeType={}", format, mimeType);

        logMemoryStats();

        Response response = null;
        try {
            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters);
            final BufferedImageStreamingOutput out =
                    new BufferedImageStreamingOutput(targetImage, format, renderParameters.getQuality());

            final Response.ResponseBuilder responseBuilder = Response.ok(out, mimeType);
            response = responseBuilder.build();

        } catch (Throwable t) {
            throwServiceException(t);
        }

        logMemoryStats();

        LOG.info("renderImageStream: exit");

        return response;
    }

    private Response renderImageFile(RenderParameters renderParameters,
                                     String format) {

        LOG.info("renderImageFile: entry, format={}", format);

        logMemoryStats();

        Response response = null;
        try {
            final String outputPathOrUri = renderParameters.getOut();
            if (outputPathOrUri == null) {
                throw new IllegalArgumentException("output file not specified");
            } else {
                Response.ResponseBuilder responseBuilder;
                final File outputFile = Utils.getFile(outputPathOrUri);
                if (outputFile.exists()) {
                    // TODO: discuss how we really want to handle existing output files
                    LOG.info("renderImageFile: output file " + outputFile.getAbsolutePath() + " already exists");
                    responseBuilder = Response.ok();
                } else {
                    final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters);
                    Utils.saveImage(targetImage, outputPathOrUri, format, renderParameters.getQuality());
                    responseBuilder = Response.created(renderParameters.getOutUri());
                }
                response = responseBuilder.build();
            }

        } catch (Throwable t) {
            throwServiceException(t);
        }

        logMemoryStats();

        LOG.info("renderImageFile: exit");

        return response;
    }

    private BufferedImage validateParametersAndRenderImage(RenderParameters renderParameters)
            throws IllegalArgumentException, IllegalStateException {

        LOG.info("validateParametersAndRenderImage: entry, renderParameters={}", renderParameters);

        renderParameters.initializeDerivedValues();
        renderParameters.validate();

        final BufferedImage targetImage = renderParameters.openTargetImage();

        Render.render(renderParameters.getTileSpecs(),
                      targetImage,
                      renderParameters.getX(),
                      renderParameters.getY(),
                      renderParameters.getRes(),
                      renderParameters.getScale(),
                      renderParameters.isAreaOffset());

        LOG.info("validateParametersAndRenderImage: exit");

        return targetImage;
    }

    private void throwServiceException(Throwable t)
            throws ServiceException {

        if (t instanceof ServiceException) {
            throw (ServiceException) t;
        } else if (t instanceof IllegalArgumentException) {
            throw new IllegalServiceArgumentException(t.getMessage(), t);
        } else {
            throw new ServiceException(t.getMessage(), t);
        }
    }

    private class BufferedImageStreamingOutput
            implements StreamingOutput {

        private BufferedImage targetImage;
        private String format;
        private float quality;

        private BufferedImageStreamingOutput(BufferedImage targetImage,
                                             String format,
                                             float quality) {
            this.targetImage = targetImage;
            this.format = format;
            this.quality = quality;
        }

        @Override
        public void write(OutputStream output)
                throws IOException, WebApplicationException {

            final ImageOutputStream imageOutputStream = new MemoryCacheImageOutputStream(output);
            Utils.writeImage(targetImage, format, quality, imageOutputStream);
        }
    }

    private void logMemoryStats() {
        if (LOG.isDebugEnabled()) {
            final Runtime runtime = Runtime.getRuntime();
            final double totalMb = runtime.totalMemory() / ONE_MEGABYTE;
            final double freeMb = runtime.freeMemory() / ONE_MEGABYTE;
            final double usedMb = totalMb - freeMb;
            LOG.debug("logMemoryStats: usedMb={}, freeMb={}, totalMb={}",
                      (long) usedMb, (long) freeMb, (long) totalMb);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private static final String IMAGE_JPEG_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_PNG_MIME_TYPE = "image/png";

    private static final int ONE_MEGABYTE = 1024 * 1024;
}
