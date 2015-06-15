package org.janelia.render.service;

import com.mongodb.MongoClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
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

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.render.service.dao.MatchDao;
import org.janelia.render.service.dao.SharedMongoClient;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APIs for accessing point match data stored in the Match service database.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class MatchService {

    private final MatchDao matchDao;

    @SuppressWarnings("UnusedDeclaration")
    public MatchService()
            throws UnknownHostException {
        this(buildMatchDao());
    }

    public MatchService(final MatchDao matchDao) {
        this.matchDao = matchDao;
    }

    @Path("matchCollection/{matchCollection}/group/{groupId}/matchesWithinGroup")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchesWithinGroup(@PathParam("owner") final String owner,
                                          @PathParam("matchCollection") final String matchCollection,
                                          @PathParam("groupId") final String groupId) {

        LOG.info("getMatchesWithinGroup: entry, owner={}, matchCollection={}, groupId={}",
                 owner, matchCollection, groupId);

        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesWithinGroup(matchCollection, groupId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("matchCollection/{matchCollection}/group/{groupId}/matchesOutsideGroup")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchesOutsideGroup(@PathParam("owner") final String owner,
                                           @PathParam("matchCollection") final String matchCollection,
                                           @PathParam("groupId") final String groupId) {

        LOG.info("getMatchesWithinGroup: entry, owner={}, matchCollection={}, groupId={}",
                 owner, matchCollection, groupId);

        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesOutsideGroup(matchCollection, groupId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("matchCollection/{matchCollection}/group/{pGroupId}/matchesWith/{qGroupId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchesBetweenGroups(@PathParam("owner") final String owner,
                                            @PathParam("matchCollection") final String matchCollection,
                                            @PathParam("pGroupId") final String pGroupId,
                                            @PathParam("qGroupId") final String qGroupId) {

        LOG.info("getMatchesBetweenGroups: entry, owner={}, matchCollection={}, pGroupId={}, qGroupId={}",
                 owner, matchCollection, pGroupId, qGroupId);

        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesBetweenGroups(matchCollection, pGroupId, qGroupId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("matchCollection/{matchCollection}/group/{pGroupId}/id/{pId}/matchesWith/{qGroupId}/id/{qId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchesBetweenObjects(@PathParam("owner") final String owner,
                                             @PathParam("matchCollection") final String matchCollection,
                                             @PathParam("pGroupId") final String pGroupId,
                                             @PathParam("pId") final String pId,
                                             @PathParam("qGroupId") final String qGroupId,
                                             @PathParam("qId") final String qId) {

        LOG.info("getMatchesBetweenObjects: entry, owner={}, matchCollection={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                 owner, matchCollection, pGroupId, pId, qGroupId, qId);

        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesBetweenObjects(matchCollection, pGroupId, pId, qGroupId, qId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("matchCollection/{matchCollection}/group/{groupId}/matchesOutsideGroup")
    @DELETE
    public Response deleteMatchesOutsideGroup(@PathParam("owner") final String owner,
                                              @PathParam("matchCollection") final String matchCollection,
                                              @PathParam("groupId") final String groupId) {

        LOG.info("deleteMatchesOutsideGroup: entry, owner={}, matchCollection={}, groupId={}",
                 owner, matchCollection, groupId);

        Response response = null;
        try {
            matchDao.removeMatchesOutsideGroup(matchCollection, groupId);
            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
     }

    @Path("matchCollection/{matchCollection}/matches")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveMatches(@PathParam("owner") final String owner,
                                @PathParam("matchCollection") final String matchCollection,
                                @Context final UriInfo uriInfo,
                                final List<CanvasMatches> canvasMatchesList) {

        LOG.info("saveMatches: entry, owner={}, matchCollection={}",
                 owner, matchCollection);

        if (canvasMatchesList == null) {
            throw new IllegalServiceArgumentException("no matches provided");
        }

        try {
            matchDao.saveMatches(matchCollection, canvasMatchesList);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        LOG.info("saveMatches: exit");

        return responseBuilder.build();
    }

    private Response streamResponse(final StreamingOutput responseOutput) {

        Response response = null;
        try {
            response = Response.ok(responseOutput).build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchService.class);

    private static MatchDao buildMatchDao()
            throws UnknownHostException {
        final MongoClient mongoClient = SharedMongoClient.getInstance();
        return new MatchDao(mongoClient);
    }

}
