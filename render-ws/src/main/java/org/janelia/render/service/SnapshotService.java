package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.janelia.render.service.dao.AdminDao;
import org.janelia.render.service.model.CollectionSnapshot;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;

/**
 * APIs for accessing snapshot data stored in the Admin database.
 *
 * @author Eric Trautman
 */
@Path("/v1/admin")
@Api(tags = {"Snapshot APIs"})
public class SnapshotService {

    private final AdminDao adminDao;

    @SuppressWarnings("UnusedDeclaration")
    public SnapshotService()
            throws UnknownHostException {
        this(AdminDao.build());
    }

    public SnapshotService(final AdminDao adminDao) {
        this.adminDao = adminDao;
    }

    @Path("snapshots")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<CollectionSnapshot> getSnapshots(@QueryParam("owner") final String owner,
                                                 @QueryParam("databaseName") final String databaseName,
                                                 @QueryParam("collectionName") final String collectionName,
                                                 @QueryParam("filterOutPersisted") final Boolean filterOutPersisted) {

        LOG.info("getSnapshots: entry, owner={}, databaseName={}, collectionName={}, filterOutPersisted={}",
                 owner, databaseName, collectionName, filterOutPersisted);

        List<CollectionSnapshot> list = null;
        try {
            list = adminDao.getSnapshots(owner, databaseName, collectionName, filterOutPersisted);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("owner/{owner}/database/{databaseName}/collection/{collectionName}/version/{version}/snapshot")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public CollectionSnapshot getSnapshot(@PathParam("owner") final String owner,
                                          @PathParam("databaseName") final String databaseName,
                                          @PathParam("collectionName") final String collectionName,
                                          @PathParam("version") final Integer version) {

        LOG.info("getSnapshot: entry, owner={}, databaseName={}, collectionName={}, version={}",
                 owner, databaseName, collectionName, version);

        CollectionSnapshot snapshot = null;
        try {

            snapshot = adminDao.getSnapshot(owner, databaseName, collectionName, version);

            if (snapshot == null) {
                throw new ObjectNotFoundException("snapshot with owner '" + owner +
                                                  "', databaseName '" + databaseName +
                                                  "', collectionName '" + collectionName +
                                                  "', and version '" + version +
                                                  " ' does not exist");
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return snapshot;
    }

    @Path("owner/{owner}/database/{databaseName}/collection/{collectionName}/version/{version}/snapshot")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveSnapshot(@PathParam("owner") final String owner,
                                 @PathParam("databaseName") final String databaseName,
                                 @PathParam("collectionName") final String collectionName,
                                 @PathParam("version") final Integer version,
                                 @Context final UriInfo uriInfo,
                                 final CollectionSnapshot snapshot) {

        LOG.info("saveSnapshot: entry, owner={}, databaseName={}, collectionName={}, version={}",
                 owner, databaseName, collectionName, version);

        try {
            if (snapshot == null) {
                throw new IllegalArgumentException("no snapshot provided");
            }

            validateConsistency("owner", owner, snapshot.getOwner());
            validateConsistency("databaseName", databaseName, snapshot.getDatabaseName());
            validateConsistency("collectionName", collectionName, snapshot.getCollectionName());
            validateConsistency("version", version, snapshot.getVersion());

            adminDao.saveSnapshot(snapshot);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        LOG.info("saveSnapshot: exit");

        return responseBuilder.build();
    }

    @Path("owner/{owner}/database/{databaseName}/collection/{collectionName}/version/{version}/snapshot")
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteSnapshot(@PathParam("owner") final String owner,
                                   @PathParam("databaseName") final String databaseName,
                                   @PathParam("collectionName") final String collectionName,
                                   @PathParam("version") final Integer version) {

        LOG.info("deleteSnapshot: entry, owner={}, databaseName={}, collectionName={}, version={}",
                 owner, databaseName, collectionName, version);

        Response response = null;
        try {

            adminDao.removeSnapshot(owner, databaseName, collectionName, version);
            response = Response.ok().build();

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    private void validateConsistency(final String context,
                                     final Object apiValue,
                                     final Object snapshotValue)
            throws IllegalArgumentException {
        if (! apiValue.equals(snapshotValue)) {
            throw new IllegalArgumentException("snapshot " + context + " value '" + snapshotValue +
                                               "' differs from API value '" + apiValue + "'");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotService.class);


}
