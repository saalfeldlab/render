package org.janelia.render.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
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

import org.bson.types.ObjectId;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
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

import static org.janelia.alignment.spec.stack.StackMetaData.StackState;
import static org.janelia.alignment.spec.stack.StackMetaData.StackState.COMPLETE;
import static org.janelia.alignment.spec.stack.StackMetaData.StackState.OFFLINE;

/**
 * APIs for accessing stack meta data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/v1")
@Api(tags = {"Stack Management APIs"})
public class StackMetaDataService {

    private final RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public StackMetaDataService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    public StackMetaDataService(final RenderDao renderDao)
            throws UnknownHostException {
        this.renderDao = renderDao;
    }

    @Path("likelyUniqueId")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "A (very likely) globally unique identifier")
    public String getUniqueId() {
        final ObjectId objectId = new ObjectId();
        return objectId.toString();
    }

    @Path("owners")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of all data owners")
    public List<String> getOwners() {
        LOG.info("getOwners: entry");
        return renderDao.getOwners();
    }

    @Path("owner/{owner}/stackIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of stack identifiers for the specified owner")
    public List<StackId> getStackIds(@PathParam("owner") final String owner) {

        LOG.info("getStackIds: entry, owner={}", owner);

        final List<StackMetaData> stackMetaDataList = getStackMetaDataListForOwner(owner);
        final List<StackId> list = new ArrayList<>(stackMetaDataList.size());
        for (final StackMetaData stackMetaData : stackMetaDataList) {
            list.add(stackMetaData.getStackId());
        }

        return list;
    }

    @Path("owner/{owner}/stacks")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of stack metadata for the specified owner")
    public List<StackMetaData> getStackMetaDataListForOwner(@PathParam("owner") final String owner) {

        LOG.info("getStackMetaDataListForOwner: entry, owner={}", owner);

        List<StackMetaData> list = null;
        try {
            list = renderDao.getStackMetaDataListForOwner(owner);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = "Stack Data APIs",
            value = "Metadata for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public StackMetaData getStackMetaData(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack) {

        LOG.info("getStackMetaData: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        StackMetaData stackMetaData = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {
                throw getStackNotFoundException(owner, project, stack);
            }
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return stackMetaData;
    }

    @Path("owner/{owner}/project/{fromProject}/stack/{fromStack}/cloneTo/{toStack}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs", "Stack Management APIs"},
            value = "Clones one stack to another",
            notes = "This operation copies all fromStack tiles and transformations to a new stack with the specified metadata.  This is a potentially long running operation (depending upon the size of the fromStack).")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "stack successfully cloned"),
            @ApiResponse(code = 400, message = "toStack already exists"),
            @ApiResponse(code = 404, message = "fromStack not found")
    })
    public Response cloneStackVersion(@PathParam("owner") final String owner,
                                      @PathParam("fromProject") final String fromProject,
                                      @PathParam("fromStack") final String fromStack,
                                      @PathParam("toStack") final String toStack,
                                      @QueryParam("z") final List<Double> zValues,
                                      @QueryParam("toProject") String toProject,
                                      @Context final UriInfo uriInfo,
                                      final StackVersion stackVersion) {

        LOG.info("cloneStackVersion: entry, owner={}, fromProject={}, fromStack={}, toProject={}, toStack={}, zValues={}, stackVersion={}",
                 owner, fromProject, fromStack, toProject, toStack, zValues, stackVersion);

        try {
            if (stackVersion == null) {
                throw new IllegalArgumentException("no stack version provided");
            }

            if (toProject == null) {
                toProject = fromProject;
            }

            final StackMetaData fromStackMetaData = getStackMetaData(owner, fromProject, fromStack);
            final StackId toStackId = new StackId(owner, toProject, toStack);

            StackMetaData toStackMetaData = renderDao.getStackMetaData(toStackId);

            if (toStackMetaData == null) {
                toStackMetaData = new StackMetaData(toStackId, stackVersion);
            } else {
                throw new IllegalArgumentException("stack " + toStackId + " already exists");
            }

            renderDao.cloneStack(fromStackMetaData.getStackId(), toStackId, zValues);
            renderDao.saveStackMetaData(toStackMetaData);

            LOG.info("cloneStackVersion: created {} from {}", toStackId, fromStackMetaData.getStackId());

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}")
    @POST  // NOTE: POST method is used because version number is auto-incremented
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs", "Stack Management APIs"},
            value = "Saves new version of stack metadata")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "stackVersion successfully created"),
            @ApiResponse(code = 400, message = "stackVersion not specified")
    })
    public Response saveStackVersion(@PathParam("owner") final String owner,
                                     @PathParam("project") final String project,
                                     @PathParam("stack") final String stack,
                                     @Context final UriInfo uriInfo,
                                     final StackVersion stackVersion) {

        LOG.info("saveStackVersion: entry, owner={}, project={}, stack={}, stackVersion={}",
                 owner, project, stack, stackVersion);

        try {
            if (stackVersion == null) {
                throw new IllegalArgumentException("no stack version provided");
            }

            final StackId stackId = new StackId(owner, project, stack);

            StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {
                stackMetaData = new StackMetaData(stackId, stackVersion);
            } else {
                stackMetaData = stackMetaData.getNextVersion(stackVersion);
            }

            renderDao.saveStackMetaData(stackMetaData);

            LOG.info("saveStackVersion: saved version number {}", stackMetaData.getCurrentVersionNumber());

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs", "Stack Management APIs"},
            value = "Deletes specified stack",
            notes = "Deletes all tiles, transformations, meta data, and unsaved snapshot data for the stack.")
    public Response deleteStack(@PathParam("owner") final String owner,
                                @PathParam("project") final String project,
                                @PathParam("stack") final String stack) {

        LOG.info("deleteStack: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {

                LOG.info("deleteStack: {} is already gone, nothing to do", stackId);

            } else {
                renderDao.removeStack(stackId, true);
            }

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}/z/{z}")
    @DELETE
    @ApiOperation(
            tags = {"Section Data APIs", "Stack Management APIs"},
            value = "Deletes all tiles in section",
            notes = "Deletes all tiles in the specified stack with the specified z value.  This operation can only be performed against stacks in the LOADING state")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack state is not LOADING"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response deleteStackSection(@PathParam("owner") final String owner,
                                       @PathParam("project") final String project,
                                       @PathParam("stack") final String stack,
                                       @PathParam("z") final Double z) {

        LOG.info("deleteStackSection: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        Response response = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);

            if (! stackMetaData.isLoading()) {
                throw new IllegalArgumentException("stack state is " + stackMetaData.getState() +
                                                   " but must be LOADING to delete data");
            }

            renderDao.removeSection(stackMetaData.getStackId(), z);

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}/state/{state}")
    @PUT
    @ApiOperation(
            tags = {"Stack Data APIs", "Stack Management APIs"},
            value = "Sets the stack's current state",
            notes = "Transitions stack from LOADING to COMPLETE to OFFLINE.  Transitioning to COMPLETE is a potentially long running operation since it creates indexes and aggregates meta data.")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "state successfully changed"),
            @ApiResponse(code = 400, message = "stack state cannot be changed because of current state"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response setStackState(@PathParam("owner") final String owner,
                                  @PathParam("project") final String project,
                                  @PathParam("stack") final String stack,
                                  @PathParam("state") final StackState state,
                                  @Context final UriInfo uriInfo) {

        LOG.info("setStackState: entry, owner={}, project={}, stack={}, state={}",
                 owner, project, stack, state);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);

            if (COMPLETE.equals(state)) {

                renderDao.ensureIndexesAndDeriveStats(stackMetaData); // also sets state to COMPLETE

            } else if (OFFLINE.equals(state)) {

                if (! COMPLETE.equals(stackMetaData.getState())) {
                    throw new IllegalArgumentException("The stack state is currently " + stackMetaData.getState() +
                                                       " but must be COMPLETE before transitioning to OFFLINE.");
                }

                stackMetaData.setState(state);
                renderDao.saveStackMetaData(stackMetaData);
                renderDao.removeStack(stackMetaData.getStackId(), false);

            } else { // LOADING

                stackMetaData.setState(state);
                renderDao.saveStackMetaData(stackMetaData);
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}/bounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs", "Stack Management APIs"},
            value = "Bounds for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack bounds not available"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Bounds getStackBounds(@PathParam("owner") final String owner,
                                 @PathParam("project") final String project,
                                 @PathParam("stack") final String stack) {

        LOG.info("getStackBounds: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Bounds bounds = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            final StackStats stats = stackMetaData.getStats();

            String errorCondition = null;

            if (stats == null) {
                errorCondition = " have not been stored.  ";
            } else {
                bounds = stats.getStackBounds();
                if (bounds == null) {
                    errorCondition = " have been stored without bounds.  ";
                }
            }

            if (errorCondition != null) {
                throw new IllegalServiceArgumentException(
                        "Stats for " + stackMetaData.getStackId() + errorCondition +
                        "If all data has been loaded for this stack, setting its state to " +
                        "COMPLETE will trigger derivation and storage of all the stack's stats.");
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return bounds;
    }

    @Path("owner/{owner}/project/{project}/stack/{stack}/tileIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Tile IDs for the specified stack",
            responseContainer = "List",
            response = String.class,
            notes = "For stacks with large numbers of tiles, this will produce a large amount of data (e.g. 500MB for 18 million tiles) - use wisely.")
    public Response getStackTileIds(@PathParam("owner") final String owner,
                                    @PathParam("project") final String project,
                                    @PathParam("stack") final String stack) {

        LOG.info("getStackTileIds: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final StreamingOutput responseOutput = new StreamingOutput() {
                @Override
                public void write(final OutputStream output)
                        throws IOException, WebApplicationException {
                    renderDao.writeTileIds(stackId, output);
                }
            };
            response = Response.ok(responseOutput).build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    public static ObjectNotFoundException getStackNotFoundException(final String owner,
                                                                    final String project,
                                                                    final String stack) {
        return new ObjectNotFoundException("stack with owner '" + owner + "', project '" + project +
                                            "', and name '" + stack + "' does not exist");
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackMetaDataService.class);
}
