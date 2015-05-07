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
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        if (stackMetaData == null) {
            RenderServiceUtil.throwServiceException(getStackNotFoundException(owner, project, stack));
        }

        return stackMetaData;
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

                renderDao.removeStack(stackId);
            }

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

    private static final Logger LOG = LoggerFactory.getLogger(StackMetaDataService.class);
}
