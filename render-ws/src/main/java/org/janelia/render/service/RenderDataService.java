package org.janelia.render.service;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.StackMetaData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.render.service.dao.RenderDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
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

    private RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public RenderDataService()
            throws UnknownHostException {
        this(RenderServiceUtil.buildDao());
    }

    public RenderDataService(RenderDao renderDao) {
        this.renderDao = renderDao;
    }

    @Path("stackIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<StackId> getStackIds(@PathParam("owner") String owner) {

        LOG.info("getStackIds: entry, owner={}", owner);

        List<StackId> list = null;
        try {
            list = renderDao.getStackIds(owner);
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
                                  @PathParam("stack") String stack,
                                  @Context UriInfo uriInfo) {

        return getLayoutFileForSectionIdRange(owner, project, stack, null, null, uriInfo);
    }

    @Path("project/{project}/stack/{stack}/sectionRange/{minSectionId},{maxSectionId}/layoutFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getLayoutFileForSectionIdRange(@PathParam("owner") final String owner,
                                                   @PathParam("project") final String project,
                                                   @PathParam("stack") final String stack,
                                                   @PathParam("minSectionId") final Integer minSectionId,
                                                   @PathParam("maxSectionId") final Integer maxSectionId,
                                                   @Context UriInfo uriInfo) {

        LOG.info("getLayoutFileForSectionIdRange: entry, owner={}, project={}, stack={}, minSectionId={}, maxSectionId={}",
                 owner, project, stack, minSectionId, maxSectionId);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final String requestUri = uriInfo.getRequestUri().toString();
            final String stackUri = "/stack/" + stack + "/";
            final int stackEnd = requestUri.indexOf(stackUri) + stackUri.length() - 1;
            final String stackRequestUri = requestUri.substring(0, stackEnd);
            final StreamingOutput responseOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output)
                        throws IOException, WebApplicationException {
                    renderDao.writeLayoutFileData(stackId, stackRequestUri, minSectionId, maxSectionId, output);
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
            list = renderDao.getZValues(stackId);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/bounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Bounds getStackBounds(@PathParam("owner") String owner,
                                 @PathParam("project") String project,
                                 @PathParam("stack") String stack) {

        LOG.info("getStackBounds: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Bounds bounds = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            bounds = renderDao.getStackBounds(stackId);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return bounds;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/bounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Bounds getLayerBounds(@PathParam("owner") String owner,
                                 @PathParam("project") String project,
                                 @PathParam("stack") String stack,
                                 @PathParam("z") Double z) {

        LOG.info("getLayerBounds: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        Bounds bounds = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            bounds = renderDao.getLayerBounds(stackId, z);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return bounds;
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
            list = renderDao.getTileBounds(stackId, z);
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

    @Path("project/{project}/stack/{stack}/tile/{tileId}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RenderParameters getRenderParameters(@PathParam("owner") String owner,
                                                @PathParam("project") String project,
                                                @PathParam("stack") String stack,
                                                @PathParam("tileId") String tileId,
                                                @QueryParam("scale") Double scale) {

        LOG.info("getRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, scale={}",
                 owner, project, stack, tileId, scale);

        RenderParameters parameters = null;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, true);
            tileSpec.flattenTransforms();
            if (scale == null) {
                scale = 1.0;
            }

            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);

            final int margin = 6;
            final Double x = getLayoutMinValue(tileSpec.getMinX(), margin);
            final Double y = getLayoutMinValue(tileSpec.getMinY(), margin);
            final Integer width = getLayoutSizeValue(stackMetaData.getLayoutWidth(), tileSpec.getWidth(), margin);
            final Integer height = getLayoutSizeValue(stackMetaData.getLayoutHeight(), tileSpec.getHeight(), margin);

            parameters = new RenderParameters(null, x, y, width, height, scale);
            parameters.addTileSpec(tileSpec);

        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return parameters;
    }

    public TileSpec getTileSpec(String owner,
                                String project,
                                String stack,
                                String tileId,
                                boolean resolveTransformReferences) {
        final StackId stackId = new StackId(owner, project, stack);
        return renderDao.getTileSpec(stackId, tileId, resolveTransformReferences);
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

            // resolve all transform references and re-derive bounding box before saving ...
            tileSpec = renderDao.resolveTransformReferencesForTiles(stackId, tileSpec);
            tileSpec.deriveBoundingBox(true);

            renderDao.saveTileSpec(stackId, tileSpec);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/transform/{transformIndex}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TransformSpec getTransformSpecForTile(@PathParam("owner") String owner,
                                                 @PathParam("project") String project,
                                                 @PathParam("stack") String stack,
                                                 @PathParam("tileId") String tileId,
                                                 @PathParam("transformIndex") Integer transformIndex) {

        LOG.info("getTransformSpecForTile: entry, owner={}, project={}, stack={}, tileId={}, transformIndex={}",
                 owner, project, stack, tileId, transformIndex);

        TransformSpec transformSpec = null;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);
            transformSpec = tileSpec.getTransforms().getSpec(transformIndex);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return transformSpec;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/transform/{transformIndex}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveTransformSpecForTile(@PathParam("owner") String owner,
                                             @PathParam("project") String project,
                                             @PathParam("stack") String stack,
                                             @PathParam("tileId") String tileId,
                                             @PathParam("transformIndex") Integer transformIndex,
                                             @Context UriInfo uriInfo,
                                             TransformSpec transformSpec) {

        LOG.info("saveTransformSpecForTile: entry, owner={}, project={}, stack={}, tileId={}, transformIndex={}",
                 owner, project, stack, tileId, transformIndex);

        if (transformSpec == null) {
            throw new IllegalServiceArgumentException("no transform spec provided");
        }

        if ((transformIndex == null) || (transformIndex < 0)) {
            throw new IllegalServiceArgumentException("non-negative transformIndex must be provided");
        }

        try {
            final StackId stackId = new StackId(owner, project, stack);
            TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);
            tileSpec.setTransformSpec(transformIndex, transformSpec);

            // Resolve all transform references and re-derive bounding box before saving.
            // NOTE: resolution is different from flattening, so referential data remains intact
            tileSpec = renderDao.resolveTransformReferencesForTiles(stackId, tileSpec);
            tileSpec.deriveBoundingBox(true);

            renderDao.saveTileSpec(stackId, tileSpec);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/transform-count")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public int getTransformCountForTile(@PathParam("owner") String owner,
                                        @PathParam("project") String project,
                                        @PathParam("stack") String stack,
                                        @PathParam("tileId") String tileId) {

        LOG.info("getTransformCountForTile: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        int count = 0;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);
            count = tileSpec.numberOfTransforms();
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return count;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/last-transform")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TransformSpec getLastTransformSpecForTile(@PathParam("owner") String owner,
                                                     @PathParam("project") String project,
                                                     @PathParam("stack") String stack,
                                                     @PathParam("tileId") String tileId) {

        LOG.info("getLastTransformSpecForTile: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        TransformSpec transformSpec = null;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);
            final int lastTransformIndex = tileSpec.numberOfTransforms() - 1;
            if (lastTransformIndex >= 0) {
                transformSpec = tileSpec.getTransforms().getSpec(lastTransformIndex);
            }
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return transformSpec;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/last-transform")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveLastTransformSpecForTile(@PathParam("owner") String owner,
                                                 @PathParam("project") String project,
                                                 @PathParam("stack") String stack,
                                                 @PathParam("tileId") String tileId,
                                                 @Context UriInfo uriInfo,
                                                 TransformSpec transformSpec) {

        LOG.info("saveLastTransformSpecForTile: entry, owner={}, project={}, stack={}, tileId={}, transformIndex={}",
                 owner, project, stack, tileId);

        if (transformSpec == null) {
            throw new IllegalServiceArgumentException("no transform spec provided");
        }

        try {
            final StackId stackId = new StackId(owner, project, stack);
            TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);
            if (tileSpec.hasTransforms()) {
                final int lastTransformIndex = tileSpec.numberOfTransforms() - 1;
                tileSpec.setTransformSpec(lastTransformIndex, transformSpec);
            } else {
                tileSpec.setTransformSpec(0, transformSpec);
            }

            // Resolve all transform references and re-derive bounding box before saving.
            // NOTE: resolution is different from flattening, so referential data remains intact
            tileSpec = renderDao.resolveTransformReferencesForTiles(stackId, tileSpec);
            tileSpec.deriveBoundingBox(true);

            renderDao.saveTileSpec(stackId, tileSpec);
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
            transformSpec = renderDao.getTransformSpec(stackId, transformId);
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
            renderDao.saveTransformSpec(stackId, transformSpec);

            // TODO: re-derive bounding boxes for all tiles that reference this transform

        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    /**
     * @return render parameters for specified bounding box with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public RenderParameters getExternalRenderParameters(@PathParam("owner") String owner,
                                                        @PathParam("project") String project,
                                                        @PathParam("stack") String stack,
                                                        @PathParam("x") Double x,
                                                        @PathParam("y") Double y,
                                                        @PathParam("z") Double z,
                                                        @PathParam("width") Integer width,
                                                        @PathParam("height") Integer height,
                                                        @PathParam("scale") Double scale) {

        LOG.info("getExternalRenderParameters: entry, owner={}, project={}, stack={}, x={}, y={}, z={}, width={}, height={}, scale={}",
                 owner, project, stack, x, y, z, width, height, scale);

        RenderParameters parameters = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            parameters = getInternalRenderParameters(stackId, x, y, z, width, height, scale);
            parameters.flattenTransforms();
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return parameters;
    }

    /**
     * @return render parameters for specified bounding box with in-memory resolved
     *         transform specs suitable for internal use.
     */
    public RenderParameters getInternalRenderParameters(final StackId stackId,
                                                        final Double x,
                                                        final Double y,
                                                        final Double z,
                                                        final Integer width,
                                                        final Integer height,
                                                        final Double scale) {

        return renderDao.getParameters(stackId, x, y, z, width, height, scale);
    }

    private Double getLayoutMinValue(Double minValue,
                                     int margin) {
        Double layoutValue = null;
        if (minValue != null) {
            layoutValue = minValue - margin;
        }
        return layoutValue;
    }

    private Integer getLayoutSizeValue(Integer stackValue,
                                       Integer tileValue,
                                       int margin) {
        Integer layoutValue = null;

        if (stackValue != null) {
            layoutValue = stackValue + margin;
        } else if ((tileValue != null) && (tileValue >= 0)) {
            layoutValue = tileValue + margin;
        }

        if ((layoutValue != null) && ((layoutValue % 2) != 0)) {
            layoutValue = layoutValue + 1;
        }

        return layoutValue;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataService.class);
}
