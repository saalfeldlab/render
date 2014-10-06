package org.janelia.render.service;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.dao.DbConfig;
import org.janelia.render.service.dao.RenderParametersDao;
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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;

/**
 * RESTful web service API for {@link Render} tool.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class RenderService {

    private RenderParametersDao renderParametersDao;

    public RenderService()
            throws UnknownHostException {
        final DbConfig dbConfig = DbConfig.fromFile(new File("render-db.properties"));
        this.renderParametersDao = new RenderParametersDao(dbConfig);
    }

    @Path("project/{projectId}/stack/{stackId}/tile/{tileId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TileSpec getTileSpec(@PathParam("owner") String owner,
                                @PathParam("projectId") String projectId,
                                @PathParam("stackId") String stackId,
                                @PathParam("tileId") String tileId) {

        LOG.info("getTileSpec: entry, projectId={}, stackId={}, tileId={}",
                 projectId, stackId, tileId);

        TileSpec tileSpec = null;
        try {
            tileSpec = renderParametersDao.getTileSpec(owner, projectId, stackId, tileId);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return tileSpec;
    }

    @Path("project/{projectId}/stack/{stackId}/tile/{tileId}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTileSpec(@PathParam("owner") String owner,
                                 @PathParam("projectId") String projectId,
                                 @PathParam("stackId") String stackId,
                                 @PathParam("tileId") String tileId,
                                 @Context UriInfo uriInfo,
                                 TileSpec tileSpec) {

        LOG.info("saveTileSpec: entry, projectId={}, stackId={}, tileId={}",
                 projectId, stackId, tileId);

        if (tileSpec == null) {
            throw new IllegalServiceArgumentException("no tile spec provided");
        } else if (! tileId.equals(tileSpec.getTileId())) {
            throw new IllegalServiceArgumentException("request tileId value (" + tileId +
                                                      ") does not match tile spec tileId value (" +
                                                      tileSpec.getTileId() + ")");
        }

        try {
            renderParametersDao.saveTileSpec(owner, projectId, stackId, tileSpec);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{projectId}/stack/{stackId}/transform/{transformId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TransformSpec getTransformSpec(@PathParam("owner") String owner,
                                          @PathParam("projectId") String projectId,
                                          @PathParam("stackId") String stackId,
                                          @PathParam("transformId") String transformId) {

        LOG.info("getTransformSpec: entry, projectId={}, stackId={}, transformId={}",
                 projectId, stackId, transformId);

        TransformSpec transformSpec = null;
        try {
            transformSpec = renderParametersDao.getTransformSpec(owner, projectId, stackId, transformId);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return transformSpec;
    }

    @Path("project/{projectId}/stack/{stackId}/transform/{transformId}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTransformSpec(@PathParam("owner") String owner,
                                      @PathParam("projectId") String projectId,
                                      @PathParam("stackId") String stackId,
                                      @PathParam("transformId") String transformId,
                                      @Context UriInfo uriInfo,
                                      TransformSpec transformSpec) {

        LOG.info("saveTransformSpec: entry, projectId={}, stackId={}, transformId={}",
                 projectId, stackId, transformId);

        if (transformSpec == null) {
            throw new IllegalServiceArgumentException("no transform spec provided");
        } else if (! transformId.equals(transformSpec.getId())) {
            throw new IllegalServiceArgumentException("request transformId value (" + transformId +
                                                      ") does not match transform spec id value (" +
                                                      transformSpec.getId() + ")");
        }

        try {
            renderParametersDao.saveTransformSpec(owner, projectId, stackId, transformSpec);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{projectId}/stack/{stackId}/z/{z}/box/{x},{y},{width},{height},{mipmapLevel}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RenderParameters getRenderParameters(@PathParam("owner") String owner,
                                                @PathParam("projectId") String projectId,
                                                @PathParam("stackId") String stackId,
                                                @PathParam("x") Double x,
                                                @PathParam("y") Double y,
                                                @PathParam("z") Double z,
                                                @PathParam("width") Integer width,
                                                @PathParam("height") Integer height,
                                                @PathParam("mipmapLevel") Integer mipmapLevel) {

        LOG.info("getRenderParameters: entry, projectId={}, stackId={}, x={}, y={}, z={}, width={}, height={}, mipmapLevel={}",
                 projectId, stackId, x, y, z, width, height, mipmapLevel);

        RenderParameters parameters = null;
        try {
            parameters = renderParametersDao.getParameters(owner, projectId, stackId, x, y, z, width, height, mipmapLevel);
        } catch (Throwable t) {
            throwServiceException(t);
        }
        return parameters;
    }

    @Path("project/{projectId}/stack/{stackId}/z/{z}/box/{x},{y},{width},{height},{mipmapLevel}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImageForBox(@PathParam("owner") String owner,
                                          @PathParam("projectId") String projectId,
                                          @PathParam("stackId") String stackId,
                                          @PathParam("x") Double x,
                                          @PathParam("y") Double y,
                                          @PathParam("z") Double z,
                                          @PathParam("width") Integer width,
                                          @PathParam("height") Integer height,
                                          @PathParam("mipmapLevel") Integer mipmapLevel) {

        LOG.info("renderJpegImageForBox: entry");
        final RenderParameters renderParameters = getRenderParameters(owner,
                                                                      projectId,
                                                                      stackId,
                                                                      x,
                                                                      y,
                                                                      z,
                                                                      width,
                                                                      height,
                                                                      mipmapLevel);
        return renderJpegImage(renderParameters);
    }

    @Path("project/{projectId}/stack/{stackId}/z/{z}/box/{x},{y},{width},{height},{mipmapLevel}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    public Response renderPngImageForBox(@PathParam("owner") String owner,
                                         @PathParam("projectId") String projectId,
                                         @PathParam("stackId") String stackId,
                                         @PathParam("x") Double x,
                                         @PathParam("y") Double y,
                                         @PathParam("z") Double z,
                                         @PathParam("width") Integer width,
                                         @PathParam("height") Integer height,
                                         @PathParam("mipmapLevel") Integer mipmapLevel) {

        LOG.info("renderPngImageForBox: entry");
        final RenderParameters renderParameters = getRenderParameters(owner,
                                                                      projectId,
                                                                      stackId,
                                                                      x,
                                                                      y,
                                                                      z,
                                                                      width,
                                                                      height,
                                                                      mipmapLevel);
        return renderPngImage(renderParameters);
    }

    @Path("jpeg-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImage(RenderParameters renderParameters) {
        LOG.info("renderJpegImage: entry, renderParameters={}", renderParameters);
        return renderImageStream(renderParameters, Utils.JPEG_FORMAT, IMAGE_JPEG_MIME_TYPE);
    }


    @Path("png-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderPngImage(RenderParameters renderParameters) {
        LOG.info("renderPngImage: entry, renderParameters={}", renderParameters);
        return renderImageStream(renderParameters, Utils.PNG_FORMAT, IMAGE_PNG_MIME_TYPE);
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
                      renderParameters.isAreaOffset(),
                      renderParameters.skipInterpolation());

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
