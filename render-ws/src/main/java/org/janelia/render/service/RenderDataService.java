package org.janelia.render.service;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.dao.RenderParametersDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.List;

/**
 * APIs for accessing tile and transform data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class RenderDataService {

    private RenderParametersDao renderParametersDao;

    @SuppressWarnings("UnusedDeclaration")
    public RenderDataService()
            throws UnknownHostException {
        this(RenderServiceUtil.buildDao());
    }

    public RenderDataService(RenderParametersDao renderParametersDao) {
        this.renderParametersDao = renderParametersDao;
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
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/layoutFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getLayoutFile(@PathParam("owner") String owner,
                                  @PathParam("project") String project,
                                  @PathParam("stack") String stack) {

        LOG.info("getLayoutFile: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StreamingOutput responseOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output)
                        throws IOException, WebApplicationException {
                    renderParametersDao.writeLayoutFileData(stackId, output);
                }
            };
            response = Response.ok(responseOutput).build();
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("project/{project}/stack/{stack}/zValues")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Double> getZValues(@PathParam("owner") String owner,
                                   @PathParam("project") String project,
                                   @PathParam("stack") String stack) {

        LOG.info("getZValues: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        List<Double> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderParametersDao.getZValues(stackId);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
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
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
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
            tileSpec = getTileSpec(owner, project, stack, tileId, false);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return tileSpec;
    }

    public TileSpec getTileSpec(String owner,
                                String project,
                                String stack,
                                String tileId,
                                boolean resolveTransformReferences) {
        final StackId stackId = new StackId(owner, project, stack);
        return renderParametersDao.getTileSpec(stackId, tileId, resolveTransformReferences);
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
            RenderServiceUtil.throwServiceException(t);
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
            RenderServiceUtil.throwServiceException(t);
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
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
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
            RenderServiceUtil.throwServiceException(t);
        }
        return parameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataService.class);
}
