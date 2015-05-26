package org.janelia.render.service;

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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.service.dao.AdminDao;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.CollectionSnapshot;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState;

/**
 * APIs for accessing stack meta data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class StackMetaDataService {

    private final RenderDao renderDao;
    private final AdminDao adminDao;

    @SuppressWarnings("UnusedDeclaration")
    public StackMetaDataService()
            throws UnknownHostException {
        this(RenderDao.build(), AdminDao.build());
    }

    public StackMetaDataService(final RenderDao renderDao,
                                final AdminDao adminDao)
            throws UnknownHostException {
        this.renderDao = renderDao;
        this.adminDao = adminDao;
    }

    @Path("stackIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<StackId> getStackIds(@PathParam("owner") final String owner) {

        LOG.info("getStackIds: entry, owner={}", owner);

        final List<StackMetaData> stackMetaDataList = getStackMetaDataListForOwner(owner);
        final List<StackId> list = new ArrayList<>(stackMetaDataList.size());
        for (StackMetaData stackMetaData : stackMetaDataList) {
            list.add(stackMetaData.getStackId());
        }

        return list;
    }

    @Path("stacks")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
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

    @Path("project/{project}/stack/{stack}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
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

    @Path("project/{project}/stack/{fromStack}/cloneTo/{toStack}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cloneStackVersion(@PathParam("owner") final String owner,
                                      @PathParam("project") final String project,
                                      @PathParam("fromStack") final String fromStack,
                                      @PathParam("toStack") final String toStack,
                                      @Context final UriInfo uriInfo,
                                      final StackVersion stackVersion) {

        LOG.info("cloneStackVersion: entry, owner={}, project={}, fromStack={}, toStack={}, stackVersion={}",
                 owner, project, fromStack, toStack, stackVersion);

        try {
            if (stackVersion == null) {
                throw new IllegalArgumentException("no stack version provided");
            }

            final StackMetaData fromStackMetaData = getStackMetaData(owner, project, fromStack);
            final StackId toStackId = new StackId(owner, project, toStack);

            StackMetaData toStackMetaData = renderDao.getStackMetaData(toStackId);

            if (toStackMetaData == null) {
                toStackMetaData = new StackMetaData(toStackId, stackVersion);
            } else {
                throw new IllegalArgumentException("stack " + toStackId + " already exists");
            }

            renderDao.cloneStack(fromStackMetaData.getStackId(), toStackId);
            renderDao.saveStackMetaData(toStackMetaData);

            LOG.info("cloneStackVersion: created {} from {}", toStackId, fromStackMetaData.getStackId());

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}")
    @POST  // NOTE: POST method is used because version number is auto-incremented
    @Consumes(MediaType.APPLICATION_JSON)
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

    @Path("project/{project}/stack/{stack}")
    @DELETE
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

                final StackVersion currentVersion = stackMetaData.getCurrentVersion();
                if (currentVersion.isSnapshotNeeded()) {
                    unRegisterSnapshots(stackMetaData);
                }

                renderDao.removeStack(stackId, true);
            }

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("project/{project}/stack/{stack}/z/{z}")
    @DELETE
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

    @Path("project/{project}/stack/{stack}/state/{state}")
    @PUT
    public Response setStackState(@PathParam("owner") final String owner,
                                  @PathParam("project") final String project,
                                  @PathParam("stack") final String stack,
                                  @PathParam("state") final StackState state,
                                  @Context final UriInfo uriInfo) {

        LOG.info("setStackState: entry, owner={}, project={}, stack={}, state={}",
                 owner, project, stack, state);

        try {
            StackMetaData stackMetaData = getStackMetaData(owner, project, stack);

            if (stackMetaData == null) {
                throw getStackNotFoundException(owner, project, stack);
            }

            if (StackState.COMPLETE.equals(state)) {

                stackMetaData = renderDao.ensureIndexesAndDeriveStats(stackMetaData);

                final StackVersion stackVersion = stackMetaData.getCurrentVersion();
                if (stackVersion.isSnapshotNeeded()) {
                    registerSnapshots(stackMetaData);
                }

            } else if (StackState.OFFLINE.equals(state)) {

                if (! StackState.COMPLETE.equals(stackMetaData.getState())) {
                    throw new IllegalArgumentException("stack state is currently " + stackMetaData.getState() +
                                                       " but must be COMPLETE before transitioning to OFFLINE");
                }

                final StackVersion currentVersion = stackMetaData.getCurrentVersion();
                if (! currentVersion.isSnapshotNeeded()) {
                    throw new IllegalArgumentException(
                            "stack does not require a snapshot so it cannot be transitioned OFFLINE");
                }

                if (! hasSavedSnapshots(stackMetaData)) {
                    throw new IllegalArgumentException(
                            "stack snapshot has not yet been saved so it cannot be transitioned OFFLINE");
                }

                stackMetaData.setState(state);
                renderDao.saveStackMetaData(stackMetaData);
                renderDao.removeStack(stackMetaData.getStackId(), false);

            } else {
                stackMetaData.setState(state);
                renderDao.saveStackMetaData(stackMetaData);
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("project/{project}/stack/{stack}/bounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
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

    public static ObjectNotFoundException getStackNotFoundException(final String owner,
                                                                    final String project,
                                                                    final String stack) {
        return new ObjectNotFoundException("stack with owner '" + owner + "', project '" + project +
                                            "', and name '" + stack + "' does not exist");
    }

    private void registerSnapshots(final StackMetaData stackMetaData) {

        final StackId stackId = stackMetaData.getStackId();

        LOG.debug("registerSnapshots: entry, {}", stackId);

        final StackStats stats = stackMetaData.getStats();
        final StackVersion stackVersion = stackMetaData.getCurrentVersion();

        final long v5AlignTPSBytesPerTile = 7033973184L / 6978148;
        final long estimatedTileBytes = stats.getTileCount() * v5AlignTPSBytesPerTile;

        final CollectionSnapshot tileSnapshot = new CollectionSnapshot(stackId.getOwner(),
                                                                       RenderDao.RENDER_DB_NAME,
                                                                       stackId.getTileCollectionName(),
                                                                       stackMetaData.getCurrentVersionNumber(),
                                                                       stackVersion.getSnapshotRootPath(),
                                                                       stackVersion.getCreateTimestamp(),
                                                                       stackVersion.getVersionNotes(),
                                                                       estimatedTileBytes);
        adminDao.saveSnapshot(tileSnapshot);

        final long v5AlignTPSBytesPerTransform = 445194400L / 2454;
        final long estimatedTransformBytes = stats.getTransformCount() * v5AlignTPSBytesPerTransform;

        final CollectionSnapshot transformSnapshot = new CollectionSnapshot(stackId.getOwner(),
                                                                            RenderDao.RENDER_DB_NAME,
                                                                            stackId.getTransformCollectionName(),
                                                                            stackMetaData.getCurrentVersionNumber(),
                                                                            stackVersion.getSnapshotRootPath(),
                                                                            stackVersion.getCreateTimestamp(),
                                                                            stackVersion.getVersionNotes(),
                                                                            estimatedTransformBytes);
        adminDao.saveSnapshot(transformSnapshot);

        LOG.debug("registerSnapshots: exit, {}", stackId);
    }

    private void unRegisterSnapshots(final StackMetaData stackMetaData) {

        final StackId stackId = stackMetaData.getStackId();

        LOG.debug("unRegisterSnapshots: entry, {}", stackId);

        List<CollectionSnapshot> unPersistedSnapshotList = adminDao.getSnapshots(stackId.getOwner(),
                                                                                 RenderDao.RENDER_DB_NAME,
                                                                                 null,
                                                                                 true);
        for (CollectionSnapshot snapshot : unPersistedSnapshotList) {
            adminDao.removeSnapshot(snapshot.getOwner(),
                                    snapshot.getDatabaseName(),
                                    snapshot.getCollectionName(),
                                    snapshot.getVersion());
        }

        LOG.debug("unRegisterSnapshots: exit, {}", stackId);
    }

    private boolean hasSavedSnapshots(final StackMetaData stackMetaData) {

        final StackId stackId = stackMetaData.getStackId();
        final Integer versionNumber = stackMetaData.getCurrentVersionNumber();

        LOG.debug("hasSavedSnapshots: entry, stackId={}, versionNumber={}", stackId, versionNumber);

        final CollectionSnapshot tileSnapshot = adminDao.getSnapshot(stackId.getOwner(),
                                                                     RenderDao.RENDER_DB_NAME,
                                                                     stackId.getTileCollectionName(),
                                                                     versionNumber);

        boolean hasSavedSnapshots = false;

        if ((tileSnapshot != null) && tileSnapshot.isSaved()) {

            final CollectionSnapshot transformSnapshot = adminDao.getSnapshot(stackId.getOwner(),
                                                                              RenderDao.RENDER_DB_NAME,
                                                                              stackId.getTransformCollectionName(),
                                                                              versionNumber);

            hasSavedSnapshots = transformSnapshot == null || transformSnapshot.isSaved();

        }

        return hasSavedSnapshots;
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackMetaDataService.class);
}
