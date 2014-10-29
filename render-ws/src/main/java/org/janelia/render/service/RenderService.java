package org.janelia.render.service;

import com.google.gson.reflect.TypeToken;
import com.mongodb.MongoClient;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileCoordinates;
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
import java.lang.reflect.Type;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RESTful web service API for {@link Render} tool.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class RenderService {

    private RenderParametersDao renderParametersDao;

    @SuppressWarnings("UnusedDeclaration")
    public RenderService()
            throws UnknownHostException {
        this(buildDao());
    }

    public RenderService(RenderParametersDao renderParametersDao) {
        this.renderParametersDao = renderParametersDao;
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
            tileSpec = renderParametersDao.getTileSpec(stackId, tileId, false);
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

    @Path("project/{project}/stack/{stack}/tile/{tileId}/local-to-world-coordinates/{x},{y}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TileCoordinates getWorldCoordinates(@PathParam("owner") String owner,
                                               @PathParam("project") String project,
                                               @PathParam("stack") String stack,
                                               @PathParam("tileId") String tileId,
                                               @PathParam("x") Double localX,
                                               @PathParam("y") Double localY) {

        LOG.info("getWorldCoordinates: entry, owner={}, project={}, stack={}, tileId={}, localX={}, localY={}",
                 owner, project, stack, tileId, localX, localY);

        TileCoordinates worldCoordinates = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final TileSpec tileSpec = renderParametersDao.getTileSpec(stackId, tileId, true);
            worldCoordinates = TileCoordinates.getWorldCoordinates(tileSpec, localX.floatValue(), localY.floatValue());
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return worldCoordinates;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/local-to-world-coordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileCoordinates> getWorldCoordinates(@PathParam("owner") String owner,
                                                     @PathParam("project") String project,
                                                     @PathParam("stack") String stack,
                                                     @PathParam("z") Double z,
                                                     List<TileCoordinates> localCoordinatesList) {

        LOG.info("getWorldCoordinates: entry, owner={}, project={}, stack={}, z={}, localCoordinatesList.size()={}",
                 owner, project, stack, z, localCoordinatesList.size());

        final long startTime = System.currentTimeMillis();
        long lastStatusTime = startTime;
        List<TileCoordinates> worldCoordinatesList = new ArrayList<TileCoordinates>(localCoordinatesList.size());
        final StackId stackId = new StackId(owner, project, stack);
        TileSpec tileSpec;
        TileCoordinates coordinates;
        String tileId;
        float[] local;
        int errorCount = 0;
        for (int i = 0; i < localCoordinatesList.size(); i++) {

            coordinates = localCoordinatesList.get(i);
            try {

                if (coordinates == null) {
                    throw new IllegalArgumentException("coordinates are missing");
                }

                tileId = coordinates.getTileId();
                if (tileId == null) {
                    throw new IllegalArgumentException("tileId is missing");
                }

                local = coordinates.getLocal();
                if (local == null) {
                    throw new IllegalArgumentException("local values are missing");
                } else if (local.length < 2) {
                    throw new IllegalArgumentException("local values must include both x and y");
                }

                tileSpec = renderParametersDao.getTileSpec(stackId, tileId, true);
                worldCoordinatesList.add(TileCoordinates.getWorldCoordinates(tileSpec, local[0], local[1]));

            } catch (Throwable t) {

                LOG.warn("getWorldCoordinates: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildLocalInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                worldCoordinatesList.add(coordinates);
            }

            if ((System.currentTimeMillis() - lastStatusTime) > COORDINATE_PROCESSING_LOG_INTERVAL) {
                lastStatusTime = System.currentTimeMillis();
                LOG.info("getWorldCoordinates: transformed {} out of {} points",
                         worldCoordinatesList.size(), localCoordinatesList.size());
            }

        }

        LOG.info("getWorldCoordinates: exit, transformed {} points with {} errors in {} ms",
                 worldCoordinatesList.size(), errorCount, (System.currentTimeMillis() - startTime));

        return worldCoordinatesList;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates/{x},{y}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TileCoordinates getLocalCoordinates(@PathParam("owner") String owner,
                                               @PathParam("project") String project,
                                               @PathParam("stack") String stack,
                                               @PathParam("x") Double worldX,
                                               @PathParam("y") Double worldY,
                                               @PathParam("z") Double z) {

        LOG.info("getLocalCoordinates: entry, owner={}, project={}, stack={}, worldX={}, worldY={}, z={}",
                 owner, project, stack, worldX, worldY, z);

        TileCoordinates localCoordinates = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final TileSpec tileSpec = renderParametersDao.getTileSpec(stackId, worldX, worldY, z);
            localCoordinates = TileCoordinates.getLocalCoordinates(tileSpec, worldX.floatValue(), worldY.floatValue());
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return localCoordinates;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileCoordinates> getLocalCoordinates(@PathParam("owner") String owner,
                                                     @PathParam("project") String project,
                                                     @PathParam("stack") String stack,
                                                     @PathParam("z") Double z,
                                                     List<TileCoordinates> worldCoordinatesList) {

        LOG.info("getLocalCoordinates: entry, owner={}, project={}, stack={}, z={}, worldCoordinatesList.size()={}",
                 owner, project, stack, z, worldCoordinatesList.size());

        final long startTime = System.currentTimeMillis();
        long lastStatusTime = startTime;
        List<TileCoordinates> localCoordinatesList = new ArrayList<TileCoordinates>(worldCoordinatesList.size());
        final StackId stackId = new StackId(owner, project, stack);
        TileSpec tileSpec;
        TileCoordinates coordinates;
        float[] world;
        int errorCount = 0;
        for (int i = 0; i < worldCoordinatesList.size(); i++) {

            coordinates = worldCoordinatesList.get(i);
            try {

                if (coordinates == null) {
                    throw new IllegalArgumentException("coordinates are missing");
                }

                world = coordinates.getWorld();
                if (world == null) {
                    throw new IllegalArgumentException("world values are missing");
                } else if (world.length < 2) {
                    throw new IllegalArgumentException("world values must include both x and y");
                }

                tileSpec = renderParametersDao.getTileSpec(stackId, (double) world[0], (double) world[1], z);
                localCoordinatesList.add(TileCoordinates.getLocalCoordinates(tileSpec, world[0], world[1]));

            } catch (Throwable t) {

                LOG.warn("getLocalCoordinates: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildWorldInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                localCoordinatesList.add(coordinates);
            }

            if ((System.currentTimeMillis() - lastStatusTime) > COORDINATE_PROCESSING_LOG_INTERVAL) {
                lastStatusTime = System.currentTimeMillis();
                LOG.info("getLocalCoordinates: inversely transformed {} out of {} points",
                         localCoordinatesList.size(), worldCoordinatesList.size());
            }

        }

        LOG.info("getLocalCoordinates: inversely transformed {} points with {} errors in {} ms",
                 localCoordinatesList.size(), errorCount, (System.currentTimeMillis() - startTime));

        return localCoordinatesList;
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
        final String context = RenderParameters.class.getName() + " instance";
        Response response;
        try {
            final RenderParameters renderParameters = RenderParameters.parseJson(json);
            renderParameters.initializeDerivedValues();
            renderParameters.validate();
            response = getParseSuccessResponse(context, String.valueOf(renderParameters));
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("validate-json/tile")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJson(String json) {
        LOG.info("validateTileJson: entry");
        final String context = TileSpec.class.getName() + " instance";
        Response response;
        try {
            final TileSpec tileSpec = TileSpec.fromJson(json);
            tileSpec.validate();
            response = getParseSuccessResponse(context, String.valueOf(tileSpec));
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("validate-json/tile-array")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJsonArray(String json) {
        LOG.info("validateTileJsonArray: entry");
        final String context = "array of " + TileSpec.class.getName() + " instances";
        Response response;
        try {
            final Type listType = new TypeToken<ArrayList<TileSpec>>(){}.getType();
            final List<TileSpec> list = JsonUtils.GSON.fromJson(json, listType);
            for (TileSpec spec : list) {
                spec.validateMipmaps();
            }
            final String value = "array of " + list.size() + " tile specifications";
            response = getParseSuccessResponse(context, value);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("validate-json/transform")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJson(String json) {
        LOG.info("validateTransformJson: entry");
        final String context = TransformSpec.class.getName() + " instance";
        Response response;
        try {
            final TransformSpec transformSpec = JsonUtils.GSON.fromJson(json, TransformSpec.class);
            transformSpec.validate();
            response = getParseSuccessResponse(context, String.valueOf(transformSpec));
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("validate-json/transform-array")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJsonArray(String json) {
        LOG.info("validateTransformJsonArray: entry");
        final String context = "array of " + TransformSpec.class.getName() + " instances";
        Response response;
        try {
            final Type listType = new TypeToken<ArrayList<TransformSpec>>(){}.getType();
            final List<TransformSpec> list = JsonUtils.GSON.fromJson(json, listType);
            final Map<String, TransformSpec> map = new HashMap<String, TransformSpec>((int) (list.size() * 1.5));
            for (TransformSpec spec : list) {
                if (spec.hasId()) {
                    map.put(spec.getId(), spec);
                }
            }
            for (TransformSpec spec : list) {
                spec.resolveReferences(map);
                spec.validate();
            }
            final String value = "array of " + list.size() + " transform specifications";
            response = getParseSuccessResponse(context, value);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("local-to-world-coordinates/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public float[] getWorldCoordinates(@PathParam("x") float x,
                                       @PathParam("y") float y,
                                       TileSpec tileSpec) {

        float[] worldCoordinates = null;
        try {
            worldCoordinates = tileSpec.getWorldCoordinates(x, y);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return worldCoordinates;
    }

    @Path("world-to-local-coordinates/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public float[] getLocalCoordinates(@PathParam("x") float x,
                                       @PathParam("y") float y,
                                       TileSpec tileSpec) {

        float[] localCoordinates = null;
        try {
            localCoordinates = tileSpec.getLocalCoordinates(x, y);
        } catch (Throwable t) {
            throwServiceException(t);
        }

        return localCoordinates;
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
                                             String context,
                                             String json) {
        final String message = "Failed to parse " + context +
                               " from JSON text.  Specific error is: " + t.getMessage() + "\n";
        String logJson = json;
        final int maxMsgLength = 1024;
        if (json.length() > maxMsgLength) {
            logJson = json.substring(0, maxMsgLength) + "...";
        }
        LOG.warn(message + "  JSON text is:\n" + logJson, t);

        final Response.ResponseBuilder responseBuilder = Response.status(Response.Status.BAD_REQUEST).entity(message);
        return responseBuilder.build();
    }

    private Response getParseSuccessResponse(String context,
                                             String value) {
        final String message = "Successfully parsed " + context + " from JSON text.  Parsed value is: " + value + ".\n";
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

    private static RenderParametersDao buildDao()
            throws UnknownHostException {
        final File dbConfigFile = new File("render-db.properties");
        final MongoClient mongoClient = SharedMongoClient.getInstance(dbConfigFile);
        return new RenderParametersDao(mongoClient);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private static final String IMAGE_JPEG_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_PNG_MIME_TYPE = "image/png";

    private static final int ONE_MEGABYTE = 1024 * 1024;

    private static final long COORDINATE_PROCESSING_LOG_INTERVAL = 5000;
}
