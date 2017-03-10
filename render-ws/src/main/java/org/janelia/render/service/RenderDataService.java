package org.janelia.render.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.LastTileTransform;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState.LOADING;

/**
 * APIs for accessing tile and transform data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
@Api(tags = {"Render Data APIs"})
public class RenderDataService {

    private final RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public RenderDataService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    public RenderDataService(final RenderDao renderDao) {
        this.renderDao = renderDao;
    }

    @Path("project/{project}/stack/{stack}/layoutFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(
            tags = "Layout Data APIs",
            value = "Get layout file text for all stack layers",
            produces = MediaType.TEXT_PLAIN)
    public Response getLayoutFile(@PathParam("owner") final String owner,
                                  @PathParam("project") final String project,
                                  @PathParam("stack") final String stack,
                                  @QueryParam("minZ") final Double minZ,
                                  @QueryParam("maxZ") final Double maxZ,
                                  @Context final UriInfo uriInfo) {

        return getLayoutFileForZRange(owner, project, stack, minZ, maxZ, uriInfo);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/layoutFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(
            tags = "Layout Data APIs",
            value = "Get layout file text for specified stack layer",
            produces = MediaType.TEXT_PLAIN)
    public Response getLayoutFileForZ(@PathParam("owner") final String owner,
                                      @PathParam("project") final String project,
                                      @PathParam("stack") final String stack,
                                      @PathParam("z") final Double z,
                                      @Context final UriInfo uriInfo) {

        return getLayoutFileForZRange(owner, project, stack, z, z, uriInfo);
    }

    @Path("project/{project}/stack/{stack}/zRange/{minZ},{maxZ}/layoutFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(
            tags = "Layout Data APIs",
            value = "Get layout file text for specified stack layers",
            produces = MediaType.TEXT_PLAIN)
    public Response getLayoutFileForZRange(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack,
                                           @PathParam("minZ") final Double minZ,
                                           @PathParam("maxZ") final Double maxZ,
                                           @Context final UriInfo uriInfo) {

        LOG.info("getLayoutFileForZRange: entry, owner={}, project={}, stack={}, minZ={}, maxZ={}",
                 owner, project, stack, minZ, maxZ);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final String requestUri = uriInfo.getRequestUri().toString();
            final String stackUri = "/stack/" + stack + "/";
            final int stackEnd = requestUri.indexOf(stackUri) + stackUri.length() - 1;
            final String stackRequestUri = requestUri.substring(0, stackEnd);
            final StreamingOutput responseOutput = new StreamingOutput() {
                @Override
                public void write(final OutputStream output)
                        throws IOException, WebApplicationException {
                    renderDao.writeLayoutFileData(stackId, stackRequestUri, minZ, maxZ, output);
                }
            };
            response = Response.ok(responseOutput).build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("project/{project}/stack/{stack}/zValues")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "List z values for specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<Double> getZValues(@PathParam("owner") final String owner,
                                   @PathParam("project") final String project,
                                   @PathParam("stack") final String stack,
                                   @QueryParam("minZ") final Double minZ,
                                   @QueryParam("maxZ") final Double maxZ) {

        LOG.info("getZValues: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        List<Double> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderDao.getZValues(stackId, minZ, maxZ);
            if (list.size() == 0) {
                // if no z values were found, make sure stack exists ...
                getStackMetaData(stackId);
            }
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/sectionData")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "List z and sectionId for all sections in specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "section data not generated"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<SectionData> getSectionData(@PathParam("owner") final String owner,
                                            @PathParam("project") final String project,
                                            @PathParam("stack") final String stack,
                                            @QueryParam("minZ") final Double minZ,
                                            @QueryParam("maxZ") final Double maxZ) {

        LOG.info("getSectionData: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        List<SectionData> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderDao.getSectionData(stackId, minZ, maxZ);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/reorderedSectionData")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "List z and sectionId for all reordered sections in specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "section data not generated"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<SectionData> getReorderedSectionData(@PathParam("owner") final String owner,
                                                     @PathParam("project") final String project,
                                                     @PathParam("stack") final String stack,
                                                     @QueryParam("minZ") final Double minZ,
                                                     @QueryParam("maxZ") final Double maxZ) {

        LOG.info("getReorderedSectionData: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        final List<SectionData> list = getSectionData(owner, project, stack, minZ, maxZ);
        final List<SectionData> filteredList = new ArrayList<>(list.size());
        int sectionIdInt;
        int zInt;
        for (final SectionData sectionData : list) {
            try {
                sectionIdInt = (int) Double.parseDouble(sectionData.getSectionId());
                zInt = sectionData.getZ().intValue();
            } catch (final Exception e) {
                throw new IllegalServiceArgumentException(
                        "reordered sections cannot be determined because " +
                        "stack contains non-standard sectionId (" + sectionData.getSectionId() +
                        ") or z value (" + sectionData.getZ() + ")", e);
            }
            if (sectionIdInt != zInt) {
                filteredList.add(sectionData);
            }
        }
        return filteredList;
    }

    @Path("project/{project}/stack/{stack}/mergedZValues")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "List z values for all merged sections in specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "section data not generated"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<Double> getMergedZValues(@PathParam("owner") final String owner,
                                         @PathParam("project") final String project,
                                         @PathParam("stack") final String stack,
                                         @QueryParam("minZ") final Double minZ,
                                         @QueryParam("maxZ") final Double maxZ) {

        LOG.info("getMergedZValues: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        final List<SectionData> sectionDataList = getSectionData(owner, project, stack, minZ, maxZ);
        final Map<Double, String> zToSectionIdMap = new HashMap<>(sectionDataList.size() * 2);
        final Set<Double> mergedZValues = new HashSet<>();

        String previousSectionIdForZ;
        for (final SectionData sectionData : sectionDataList) {
            previousSectionIdForZ = zToSectionIdMap.put(sectionData.getZ(), sectionData.getSectionId());
            if (previousSectionIdForZ != null) {
                mergedZValues.add(sectionData.getZ());
            }
        }

        final List<Double> sortedZList = new ArrayList<>(mergedZValues);
        Collections.sort(sortedZList);

        return sortedZList;
    }

    @Path("project/{project}/stack/{stack}/mergeableData")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "List plain text data for all mergeable sections in specified stack",
            notes = "Data format is 'toIndex < fromIndex : toZ < fromZ'.  Only layers with both an integral (.0) and at least one non-integral (e.g. .1) section are included.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public String getMergeableData(@PathParam("owner") final String owner,
                                   @PathParam("project") final String project,
                                   @PathParam("stack") final String stack,
                                   @QueryParam("minZ") final Double minZ,
                                   @QueryParam("maxZ") final Double maxZ) {

        LOG.info("getMergeableData: entry, owner={}, project={}, stack={}, minZ={}, maxZ={}",
                 owner, project, stack, minZ, maxZ);

        final List<Double> list = getZValues(owner, project, stack, minZ, maxZ);

        final StringBuilder mergeableData = new StringBuilder(list.size() * 10);
        Double z;
        int lastIntegralIndex = -1;
        Double lastIntegralZ = -1.0;
        for (int i = 0; i < list.size(); i++) {
            z = list.get(i);
            if ((z - z.intValue()) > 0) {
                if (z.intValue() == lastIntegralZ.intValue()) {
                    mergeableData.append(lastIntegralIndex).append(" < ").append(i).append(" : ");
                    mergeableData.append(lastIntegralZ).append(" < ").append(z).append('\n');
                } else {
                    LOG.warn("getMergeableData: z {} is missing corresponding high dose (.0) section", z);
                }
            } else {
                lastIntegralIndex = i;
                lastIntegralZ = z;
            }
        }

        return mergeableData.toString();
    }

    @Path("project/{project}/stack/{stack}/mergeableZValues")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "List z values for all mergeable layers in specified stack",
            notes = "Only layers with both an integral (.0) and at least one non-integral (e.g. .1) section are included.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<Double> getMergeableZValues(@PathParam("owner") final String owner,
                                            @PathParam("project") final String project,
                                            @PathParam("stack") final String stack,
                                            @QueryParam("minZ") final Double minZ,
                                            @QueryParam("maxZ") final Double maxZ) {

        final List<Double> list = getZValues(owner, project, stack, minZ, maxZ);
        final LinkedHashSet<Double> filteredSet = new LinkedHashSet<>(list.size());
        Double lastIntegralZ = -1.0;
        for (final Double z : list) {
            if ((z - z.intValue()) > 0) {
                if (z.intValue() == lastIntegralZ.intValue()) {
                    filteredSet.add(lastIntegralZ);
                    filteredSet.add(z);
                } else {
                    LOG.warn("getMergeableZValues: z {} is missing corresponding integral (.0) section", z);
                }
            } else {
                lastIntegralZ = z;
            }
        }

        LOG.info("getMergeableZValues: returning {} values", filteredSet.size());

        return new ArrayList<>(filteredSet);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/bounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get bounds for section with specified z")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Bounds getLayerBounds(@PathParam("owner") final String owner,
                                 @PathParam("project") final String project,
                                 @PathParam("stack") final String stack,
                                 @PathParam("z") final Double z) {

        LOG.info("getLayerBounds: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        Bounds bounds = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            bounds = renderDao.getLayerBounds(stackId, z);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return bounds;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/tileBounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get bounds for each tile with specified z")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<TileBounds> getTileBoundsForZ(@PathParam("owner") final String owner,
                                              @PathParam("project") final String project,
                                              @PathParam("stack") final String stack,
                                              @PathParam("z") final Double z) {

        LOG.info("getTileBoundsForZ: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        List<TileBounds> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderDao.getTileBoundsForZ(stackId, z);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/tileIds")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Set z value for specified tiles (e.g. to split a layer)")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack not in LOADING state"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response updateZForTiles(@PathParam("owner") final String owner,
                                    @PathParam("project") final String project,
                                    @PathParam("stack") final String stack,
                                    @PathParam("z") final Double z,
                                    @Context final UriInfo uriInfo,
                                    final List<String> tileIds) {
        LOG.info("updateZForTiles: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            if (! stackMetaData.isLoading()) {
                throw new IllegalStateException("Z values can only be updated for stacks in the " +
                                                LOADING + " state, but this stack's state is " +
                                                stackMetaData.getState() + ".");
            }

            renderDao.updateZForTiles(stackId, z, tileIds);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        LOG.info("updateZForTiles: exit");

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/z/{z}/resolvedTiles")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get raw tile and transform specs for section with specified z")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "too many (> 50,000) tiles in section"),
            @ApiResponse(code = 404, message = "no tile specs found"),
    })
    public ResolvedTileSpecCollection getResolvedTiles(@PathParam("owner") final String owner,
                                                       @PathParam("project") final String project,
                                                       @PathParam("stack") final String stack,
                                                       @PathParam("z") final Double z) {

        LOG.info("getResolvedTiles: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        ResolvedTileSpecCollection resolvedTiles = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            resolvedTiles = renderDao.getResolvedTiles(stackId, z);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return resolvedTiles;
    }

    @Path("project/{project}/stack/{stack}/resolvedTiles")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "Get raw tile and transform specs for specified group or bounding box")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "too many (> 50,000) matching tiles found"),
            @ApiResponse(code = 404, message = "no tile specs found"),
    })
    public ResolvedTileSpecCollection getResolvedTiles(@PathParam("owner") final String owner,
                                                       @PathParam("project") final String project,
                                                       @PathParam("stack") final String stack,
                                                       @QueryParam("minZ") final Double minZ,
                                                       @QueryParam("maxZ") final Double maxZ,
                                                       @QueryParam("groupId") final String groupId,
                                                       @QueryParam("minX") final Double minX,
                                                       @QueryParam("maxX") final Double maxX,
                                                       @QueryParam("minY") final Double minY,
                                                       @QueryParam("maxY") final Double maxY) {

        LOG.info("getResolvedTiles: entry, owner={}, project={}, stack={}, minZ={}, maxZ={}, groupId={}, minX={}, maxX={}, minY={}, maxY={}",
                 owner, project, stack, minZ, maxZ, groupId, minX, maxX, minY, maxY);

        ResolvedTileSpecCollection resolvedTiles = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            resolvedTiles = renderDao.getResolvedTiles(stackId, minZ, maxZ, groupId, minX, maxX, minY, maxY);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return resolvedTiles;
    }

    @Path("project/{project}/stack/{stack}/resolvedTiles")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "Save specified raw tile and transform specs")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack not in LOADING state, invalid data provided"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response saveResolvedTiles(@PathParam("owner") final String owner,
                                      @PathParam("project") final String project,
                                      @PathParam("stack") final String stack,
                                      @Context final UriInfo uriInfo,
                                      final ResolvedTileSpecCollection resolvedTiles) {
        return saveResolvedTilesForZ(owner, project, stack, null, uriInfo, resolvedTiles);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/resolvedTiles")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Save specified raw tile and transform specs for section")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack not in LOADING state, invalid data provided"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response saveResolvedTilesForZ(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("z") final Double z,
                                          @Context final UriInfo uriInfo,
                                          final ResolvedTileSpecCollection resolvedTiles) {

        LOG.info("saveResolvedTilesForZ: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        try {
            if (resolvedTiles == null) {
                throw new IllegalServiceArgumentException("no resolved tiles provided");
            }

            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            if (! stackMetaData.isLoading()) {
                throw new IllegalStateException("Resolved tiles can only be saved to stacks in the " +
                                                LOADING + " state, but this stack's state is " +
                                                stackMetaData.getState() + ".");
            }

            resolvedTiles.validateCollection(z);

            renderDao.saveResolvedTiles(stackId, resolvedTiles);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        LOG.info("saveResolvedTilesForZ: exit");

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/section/{sectionId}/z")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get z value for section")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack or section not found"),
    })
    public Double getZForSection(@PathParam("owner") final String owner,
                                 @PathParam("project") final String project,
                                 @PathParam("stack") final String stack,
                                 @PathParam("sectionId") final String sectionId,
                                 @Context final UriInfo uriInfo) {

        LOG.info("getZForSection: entry, owner={}, project={}, stack={}, sectionId={}",
                 owner, project, stack, sectionId);

        Double z = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            z = renderDao.getZForSection(stackId, sectionId);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return z;
    }

    @Path("project/{project}/stack/{stack}/section/{sectionId}/z")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Set z value for section")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack not in LOADING state"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response updateZForSection(@PathParam("owner") final String owner,
                                      @PathParam("project") final String project,
                                      @PathParam("stack") final String stack,
                                      @PathParam("sectionId") final String sectionId,
                                      @Context final UriInfo uriInfo,
                                      final Double z) {
        LOG.info("updateZForSection: entry, owner={}, project={}, stack={}, sectionId={}, z={}",
                 owner, project, stack, sectionId, z);

        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            if (! stackMetaData.isLoading()) {
                throw new IllegalStateException("Z values can only be updated for stacks in the " +
                                                LOADING + " state, but this stack's state is " +
                                                stackMetaData.getState() + ".");
            }

            renderDao.updateZForSection(stackId, sectionId, z);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        LOG.info("updateZForSection: exit");

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/section/{sectionId}/tileBounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get bounds for each tile with specified sectionId")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public List<TileBounds> getTileBoundsForSection(@PathParam("owner") final String owner,
                                                    @PathParam("project") final String project,
                                                    @PathParam("stack") final String stack,
                                                    @PathParam("sectionId") final String sectionId) {

        LOG.info("getTileBoundsForSection: entry, owner={}, project={}, stack={}, sectionId={}",
                 owner, project, stack, sectionId);

        List<TileBounds> list = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            list = renderDao.getTileBoundsForSection(stackId, sectionId);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("project/{project}/stack/{stack}/transform/{transformId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Transform Data APIs",
            value = "Get transform spec")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "transform or stack not found")
    })
    public TransformSpec getTransformSpec(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("transformId") final String transformId) {

        LOG.info("getTransformSpec: entry, owner={}, project={}, stack={}, transformId={}",
                 owner, project, stack, transformId);

        TransformSpec transformSpec = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            transformSpec = renderDao.getTransformSpec(stackId, transformId);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return transformSpec;
    }

    /**
     * @return number of tiles within the specified bounding box.
     */
    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height}/tile-count")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Bounding Box Data APIs",
            value = "Get number of tiles within box")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Long getTileCount(@PathParam("owner") final String owner,
                             @PathParam("project") final String project,
                             @PathParam("stack") final String stack,
                             @PathParam("x") final Double x,
                             @PathParam("y") final Double y,
                             @PathParam("z") final Double z,
                             @PathParam("width") final Integer width,
                             @PathParam("height") final Integer height) {

        LOG.info("getTileCount: entry, owner={}, project={}, stack={}, x={}, y={}, z={}, width={}, height={}",
                 owner, project, stack, x, y, z, width, height);

        long count = 0;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            count = renderDao.getTileCount(stackId, x, y, z, width, height);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return count;
    }

    /**
     * @return number of tiles within the specified bounding box.
     */
    @Path("project/{project}/stack/{stack}/dvid/imagetile/raw/xy/{width}_{height}/{x}_{y}_{z}/tile-count")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Bounding Box Data APIs", "DVID Style APIs"},
            value = "DVID style API to get number of tiles within box")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Long getTileCountForDvidBox(@PathParam("owner") final String owner,
                                       @PathParam("project") final String project,
                                       @PathParam("stack") final String stack,
                                       @PathParam("x") final Double x,
                                       @PathParam("y") final Double y,
                                       @PathParam("z") final Double z,
                                       @PathParam("width") final Integer width,
                                       @PathParam("height") final Integer height) {

        return getTileCount(owner, project, stack, x, y, z, width, height);
    }

    /**
     * @return list of tile specs for specified layer with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/z/{z}/tile-specs")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get flattened tile specs with the specified z",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack or tiles with z not found")
    })
    public List<TileSpec> getTileSpecsForZ(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack,
                                           @PathParam("z") final Double z) {

        LOG.info("getTileSpecsForZ: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        List<TileSpec> tileSpecList = null;
        try {
            final RenderParameters parameters = getRenderParametersForZ(owner, project, stack, z, 1.0, false);
            tileSpecList = parameters.getTileSpecs();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return tileSpecList;
    }

    /**
     * @return list of tile specs for specified layer with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/z/{z}/last-tile-transforms")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get last transform for each tile spec with the specified z")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack or tiles with z not found")
    })
    public List<LastTileTransform> getLastTileTransformsForZ(@PathParam("owner") final String owner,
                                                             @PathParam("project") final String project,
                                                             @PathParam("stack") final String stack,
                                                             @PathParam("z") final Double z) {

        LOG.info("getLastTileTransformsForZ: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        List<LastTileTransform> lastTileTransformList = null;
        try {
            final RenderParameters parameters = getRenderParametersForZ(owner, project, stack, z, 1.0, false);
            final List<TileSpec> tileSpecList = parameters.getTileSpecs();
            lastTileTransformList = new ArrayList<>(tileSpecList.size());
            for (final TileSpec tileSpec : tileSpecList) {
                lastTileTransformList.add(new LastTileTransform(tileSpec.getTileId(),
                                                                tileSpec.getLastTransform()));
            }
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }


        return lastTileTransformList;
    }

    /**
     * @return render parameters for specified layer with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/z/{z}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Section Data APIs",
            value = "Get parameters to render all tiles with the specified z",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack or tiles with z not found")
    })
    public RenderParameters getRenderParametersForZ(@PathParam("owner") final String owner,
                                                    @PathParam("project") final String project,
                                                    @PathParam("stack") final String stack,
                                                    @PathParam("z") final Double z,
                                                    @QueryParam("scale") final Double scale,
                                                    @QueryParam("filter") final Boolean filter) {

        LOG.info("getRenderParametersForZ: entry, owner={}, project={}, stack={}, z={}, scale={}",
                 owner, project, stack, z, scale);

        RenderParameters parameters = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            parameters = renderDao.getParameters(stackId, z, scale);
            parameters.setDoFilter(filter);

            final MipmapPathBuilder mipmapPathBuilder = stackMetaData.getCurrentMipmapPathBuilder();
            if (mipmapPathBuilder != null) {
                parameters.setMipmapPathBuilder(mipmapPathBuilder);
            }
            parameters.flattenTransforms();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return parameters;
    }

    /**
     * @return render parameters for specified bounding box with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Bounding Box Data APIs",
            value = "Get parameters to render all tiles within the specified box",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public RenderParameters getExternalRenderParameters(@PathParam("owner") final String owner,
                                                        @PathParam("project") final String project,
                                                        @PathParam("stack") final String stack,
                                                        @PathParam("x") final Double x,
                                                        @PathParam("y") final Double y,
                                                        @PathParam("z") final Double z,
                                                        @PathParam("width") final Integer width,
                                                        @PathParam("height") final Integer height,
                                                        @PathParam("scale") final Double scale,
                                                        @QueryParam("filter") final Boolean filter,
                                                        @QueryParam("binaryMask") final Boolean binaryMask,
                                                        @QueryParam("convertToGray") final Boolean convertToGray) {

        return getExternalRenderParameters(owner, project, stack, null, x, y, z, width, height, scale, filter, binaryMask, convertToGray);
    }

    /**
     * @return render parameters for specified bounding box with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/dvid/imagetile/raw/xy/{width}_{height}/{x}_{y}_{z}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Bounding Box Data APIs", "DVID Style APIs"},
            value = "DVID style API to get parameters to render all tiles within the specified box",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public RenderParameters getExternalRenderParametersForDvidBox(@PathParam("owner") final String owner,
                                                                  @PathParam("project") final String project,
                                                                  @PathParam("stack") final String stack,
                                                                  @PathParam("x") final Double x,
                                                                  @PathParam("y") final Double y,
                                                                  @PathParam("z") final Double z,
                                                                  @PathParam("width") final Integer width,
                                                                  @PathParam("height") final Integer height,
                                                                  @QueryParam("scale") final Double scale,
                                                                  @QueryParam("filter") final Boolean filter,
                                                                  @QueryParam("binaryMask") final Boolean binaryMask,
                                                                  @QueryParam("convertToGray") final Boolean convertToGray) {
        return getExternalRenderParameters(owner, project, stack, null, x, y, z, width, height, scale, filter, binaryMask, convertToGray);
    }

    /**
     * @return render parameters for specified bounding box with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Bounding Box Data APIs",
            value = "Get parameters to render all tiles within the specified group and box",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public RenderParameters getExternalRenderParameters(@PathParam("owner") final String owner,
                                                        @PathParam("project") final String project,
                                                        @PathParam("stack") final String stack,
                                                        @PathParam("groupId") final String groupId,
                                                        @PathParam("x") final Double x,
                                                        @PathParam("y") final Double y,
                                                        @PathParam("z") final Double z,
                                                        @PathParam("width") final Integer width,
                                                        @PathParam("height") final Integer height,
                                                        @PathParam("scale") final Double scale,
                                                        @QueryParam("filter") final Boolean filter,
                                                        @QueryParam("binaryMask") final Boolean binaryMask,
                                                        @QueryParam("convertToGray") final Boolean convertToGray) {

        LOG.info("getExternalRenderParameters: entry, owner={}, project={}, stack={}, groupId={}, x={}, y={}, z={}, width={}, height={}, scale={}, filter={}, binaryMask={}, convertToGray={}",
                 owner, project, stack, groupId, x, y, z, width, height, scale, filter, binaryMask, convertToGray);

        RenderParameters parameters = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            parameters = getInternalRenderParameters(stackId, groupId, x, y, z, width, height, scale);
            parameters.flattenTransforms();
            parameters.setDoFilter(filter);
            parameters.setBinaryMask(binaryMask);
            parameters.setConvertToGray(convertToGray);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return parameters;
    }

    /**
     * @return render parameters for specified bounding box with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("project/{project}/stack/{stack}/group/{groupId}/dvid/imagetile/raw/xy/{width}_{height}/{x}_{y}_{z}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Bounding Box Data APIs", "DVID Style APIs"},
            value = "DVID style API to get parameters to render all tiles within the specified group and box",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "stack not found")
    })
    public RenderParameters getExternalRenderParametersForDvidBox(@PathParam("owner") final String owner,
                                                                  @PathParam("project") final String project,
                                                                  @PathParam("stack") final String stack,
                                                                  @PathParam("groupId") final String groupId,
                                                                  @PathParam("x") final Double x,
                                                                  @PathParam("y") final Double y,
                                                                  @PathParam("z") final Double z,
                                                                  @PathParam("width") final Integer width,
                                                                  @PathParam("height") final Integer height,
                                                                  @QueryParam("scale") final Double scale,
                                                                  @QueryParam("filter") final Boolean filter,
                                                                  @QueryParam("binaryMask") final Boolean binaryMask,
                                                                  @QueryParam("convertToGray") final Boolean convertToGray) {
        return getExternalRenderParameters(owner, project, stack, groupId, x, y, z, width, height, scale, filter, binaryMask, convertToGray);
    }

    /**
     * @return render parameters for specified bounding box with in-memory resolved
     *         transform specs suitable for internal use.
     */
    public RenderParameters getInternalRenderParameters(final StackId stackId,
                                                        final String groupId,
                                                        final Double x,
                                                        final Double y,
                                                        final Double z,
                                                        final Integer width,
                                                        final Integer height,
                                                        final Double scale)
            throws ObjectNotFoundException {


        final RenderParameters parameters = renderDao.getParameters(stackId, groupId, x, y, z, width, height, scale);
        final StackMetaData stackMetaData = getStackMetaData(stackId);
        final MipmapPathBuilder mipmapPathBuilder = stackMetaData.getCurrentMipmapPathBuilder();
        if (mipmapPathBuilder != null) {
            parameters.setMipmapPathBuilder(mipmapPathBuilder);
        }
        return parameters;
    }

    public StackMetaData getStackMetaData(final StackId stackId)
            throws ObjectNotFoundException {

        final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
        if (stackMetaData == null) {
            throw StackMetaDataService.getStackNotFoundException(stackId.getOwner(),
                                                                 stackId.getProject(),
                                                                 stackId.getStack());
        }
        return stackMetaData;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderDataService.class);
}
