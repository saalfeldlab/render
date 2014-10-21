package org.janelia.render.service;

import com.mongodb.MongoClient;
import mpicbg.models.Point;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.dao.RenderParametersDao;
import org.janelia.render.service.dao.SharedMongoClient;
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
import java.util.List;

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
        final File dbConfigFile = new File("render-db.properties");
        final MongoClient mongoClient = SharedMongoClient.getInstance(dbConfigFile);
        this.renderParametersDao = new RenderParametersDao(mongoClient);
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TileSpec getTileSpec(@PathParam("owner") String owner,
                                @PathParam("project") String project,
                                @PathParam("stack") String stack,
                                @PathParam("tileId") String tileId) {

        LOG.info("getTileSpec: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        TileSpec tileSpec = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            tileSpec = renderParametersDao.getTileSpec(stackId, tileId);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return tileSpec;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTileSpec(@PathParam("owner") String owner,
                                 @PathParam("project") String project,
                                 @PathParam("stack") String stack,
                                 @PathParam("tileId") String tileId,
                                 @Context UriInfo uriInfo,
                                 TileSpec tileSpec) {

        LOG.info("saveTileSpec: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        if (tileSpec == null) {
            throw new IllegalServiceArgumentException("no tile spec provided");
        } else if (! tileId.equals(tileSpec.getTileId())) {
            throw new IllegalServiceArgumentException("request tileId value (" + tileId +
                                                      ") does not match tile spec tileId value (" +
                                                      tileSpec.getTileId() + ")");
        }

        try {
            final StackId stackId = new StackId(owner, project, stack);
            renderParametersDao.saveTileSpec(stackId, tileSpec);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/transform/{transformId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TransformSpec getTransformSpec(@PathParam("owner") String owner,
                                          @PathParam("project") String project,
                                          @PathParam("stack") String stack,
                                          @PathParam("transformId") String transformId) {

        LOG.info("getTransformSpec: entry, owner={}, project={}, stack={}, transformId={}",
                 owner, project, stack, transformId);

        TransformSpec transformSpec = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            transformSpec = renderParametersDao.getTransformSpec(stackId, transformId);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return transformSpec;
    }

    @Path("project/{project}/stack/{stack}/transform/{transformId}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTransformSpec(@PathParam("owner") String owner,
                                      @PathParam("project") String project,
                                      @PathParam("stack") String stack,
                                      @PathParam("transformId") String transformId,
                                      @Context UriInfo uriInfo,
                                      TransformSpec transformSpec) {

        LOG.info("saveTransformSpec: entry, owner={}, project={}, stack={}, transformId={}",
                 owner, project, stack, transformId);

        if (transformSpec == null) {
            throw new IllegalServiceArgumentException("no transform spec provided");
        } else if (! transformId.equals(transformSpec.getId())) {
            throw new IllegalServiceArgumentException("request transformId value (" + transformId +
                                                      ") does not match transform spec id value (" +
                                                      transformSpec.getId() + ")");
        }

        try {
            final StackId stackId = new StackId(owner, project, stack);
            renderParametersDao.saveTransformSpec(stackId, transformSpec);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("stackIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<StackId> getStackIds(@PathParam("owner") String owner) {

        LOG.info("getStackIds: entry, owner={}", owner);

        List<StackId> list = null;
        try {
            list = renderParametersDao.getStackIds(owner);
        } catch (Throwable t) {
            throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/zValues")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Double> getZValues(@PathParam("owner") String owner,
                                   @PathParam("project") String project,
                                   @PathParam("stack") String stack) {

        LOG.info("getTileBounds: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        List<Double> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderParametersDao.getZValues(stackId);
        } catch (Throwable t) {
            throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/tileBounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileBounds> getTileBounds(@PathParam("owner") String owner,
                                          @PathParam("project") String project,
                                          @PathParam("stack") String stack,
                                          @PathParam("z") Double z) {

        LOG.info("getTileBounds: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        List<TileBounds> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderParametersDao.getTileBounds(stackId, z);
        } catch (Throwable t) {
            throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RenderParameters getRenderParameters(@PathParam("owner") String owner,
                                                @PathParam("project") String project,
                                                @PathParam("stack") String stack,
                                                @PathParam("x") Double x,
                                                @PathParam("y") Double y,
                                                @PathParam("z") Double z,
                                                @PathParam("width") Integer width,
                                                @PathParam("height") Integer height,
                                                @PathParam("scale") Double scale) {

        LOG.info("getRenderParameters: entry, owner={}, project={}, stack={}, x={}, y={}, z={}, width={}, height={}, scale={}",
                 owner, project, stack, x, y, z, width, height, scale);

        RenderParameters parameters = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            parameters = renderParametersDao.getParameters(stackId, x, y, z, width, height, scale);
        } catch (Throwable t) {
            throwServiceException(t);
        }
        return parameters;
    }

    @Path("project/{projectId}/stack/{stackId}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
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
                                          @PathParam("scale") Double scale) {

        LOG.info("renderJpegImageForBox: entry");
        final RenderParameters renderParameters = getRenderParameters(owner,
                                                                      projectId,
                                                                      stackId,
                                                                      x,
                                                                      y,
                                                                      z,
                                                                      width,
                                                                      height,
                                                                      scale);
        return renderJpegImage(renderParameters);
    }

    @Path("project/{projectId}/stack/{stackId}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
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
                                         @PathParam("scale") Double scale) {

        LOG.info("renderPngImageForBox: entry");
        final RenderParameters renderParameters = getRenderParameters(owner,
                                                                      projectId,
                                                                      stackId,
                                                                      x,
                                                                      y,
                                                                      z,
                                                                      width,
                                                                      height,
                                                                      scale);
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

    @Path("validate-json/render")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateRenderParametersJson(String json) {
        LOG.info("validateRenderParametersJson: entry");
        Response response;
        try {
            final RenderParameters renderParameters = JsonUtils.GSON.fromJson(json, RenderParameters.class);
            renderParameters.initializeDerivedValues();
            renderParameters.validate();
            response = getParseSuccessResponse(renderParameters);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, RenderParameters.class.getName(), json);
        }
        return response;
    }

    @Path("validate-json/tile")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJson(String json) {
        LOG.info("validateTileJson: entry");
        Response response;
        try {
            final TileSpec tileSpec = JsonUtils.GSON.fromJson(json, TileSpec.class);
            tileSpec.validate();
            response = getParseSuccessResponse(tileSpec);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, TileSpec.class.getName(), json);
        }
        return response;
    }

    @Path("validate-json/transform")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJson(String json) {
        LOG.info("validateTransformJson: entry");
        Response response;
        try {
            final TransformSpec transformSpec = JsonUtils.GSON.fromJson(json, TransformSpec.class);
            transformSpec.validate();
            response = getParseSuccessResponse(transformSpec);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, TransformSpec.class.getName(), json);
        }
        return response;
    }

    @Path("transformed-point/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public float[] getTransformedPoint(@PathParam("x") float x,
                                       @PathParam("y") float y,
                                       TileSpec tileSpec) {

        float[] worldCoordinates = null;
        try {
            final Point p = new Point(new float[]{x, y});
            p.apply(tileSpec.createTransformList());
            final float[] w = p.getW();
            final Double z = tileSpec.getZ();
            if (z == null) {
                worldCoordinates = w;
            } else {
                worldCoordinates = new float[]{w[0], w[1], z.floatValue()};
            }
        } catch (Throwable t) {
            throwServiceException(t);
        }

        LOG.info("getTransformedPoint: returning {} for ({},{})", worldCoordinates, x, y);

        return worldCoordinates;
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

    private Response getParseFailureResponse(Throwable t,
                                             String className,
                                             String json) {
        final String message = "Failed to parse " + className +
                               " instance from JSON text.  Specific error is: " + t.getMessage();
        String logJson = json;
        final int maxMsgLength = 1024;
        if (json.length() > maxMsgLength) {
            logJson = json.substring(0, maxMsgLength) + "...";
        }
        LOG.warn(message + "  JSON text is:\n" + logJson, t);

        final Response.ResponseBuilder responseBuilder = Response.status(Response.Status.BAD_REQUEST).entity(message);
        return responseBuilder.build();
    }

    private Response getParseSuccessResponse(Object instance) {
        final String message = "Successfully parsed " + instance.getClass().getName() +
                               " instance from JSON text.  Parsed value is " + instance;
        LOG.info(message);
        final Response.ResponseBuilder responseBuilder = Response.ok(message);
        return responseBuilder.build();
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
