package org.janelia.render.service;

import com.mongodb.MongoClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.MatchCollectionMetaData;
import org.janelia.render.service.dao.MatchDao;
import org.janelia.render.service.dao.SharedMongoClient;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * APIs for accessing point match data stored in the Match service database.
 *
 * @author Eric Trautman
 */
@Path("/v1")
@Api(tags = {"Point Match APIs"})
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

    @Path("matchCollectionOwners")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of all data owners")
    public Set<String> getOwners() {

        LOG.info("getOwners: entry");

        final List<MatchCollectionMetaData> metaDataList = matchDao.getMatchCollectionMetaData();
        final Set<String> owners = new LinkedHashSet<>(metaDataList.size());
        for (final MatchCollectionMetaData metaData : metaDataList) {
            owners.add(metaData.getOwner());
        }

        return owners;
    }

    @Path("owner/{owner}/matchCollections")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of match collection metadata for the specified owner")
    public List<MatchCollectionMetaData> getMatchCollections(@PathParam("owner") final String owner) {

        LOG.info("getMatchCollections: entry, owner={}", owner);

        if (owner == null) {
            throw new IllegalArgumentException("owner must be specified");
        }

        final List<MatchCollectionMetaData> metaDataList = matchDao.getMatchCollectionMetaData();
        final List<MatchCollectionMetaData> ownerMetaDataList = new ArrayList<>(metaDataList.size());
        for (final MatchCollectionMetaData metaData : metaDataList) {
            if (owner.equals(metaData.getOwner())) {
                ownerMetaDataList.add(metaData);
            }
        }

        return ownerMetaDataList;
    }

    @Path("owner/{owner}/matchCollection/{matchCollection}/group/{groupId}/matchesWithinGroup")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Find matches within the specified group",
            notes = "Find all matches where both tiles are in the specified layer.",
            response = CanvasMatches.class,
            responseContainer="List")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Match collection not found")
    })
    public Response getMatchesWithinGroup(@PathParam("owner") final String owner,
                                          @PathParam("matchCollection") final String matchCollection,
                                          @PathParam("groupId") final String groupId) {

        LOG.info("getMatchesWithinGroup: entry, owner={}, matchCollection={}, groupId={}",
                 owner, matchCollection, groupId);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);
        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesWithinGroup(collectionId, groupId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("owner/{owner}/matchCollection/{matchCollection}/group/{groupId}/matchesOutsideGroup")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Find matches outside the specified group",
            notes = "Find all matches with one tile in the specified layer and another tile outside that layer.",
            response = CanvasMatches.class,
            responseContainer="List")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Match collection not found")
    })
    public Response getMatchesOutsideGroup(@PathParam("owner") final String owner,
                                           @PathParam("matchCollection") final String matchCollection,
                                           @PathParam("groupId") final String groupId) {

        LOG.info("getMatchesOutsideGroup: entry, owner={}, matchCollection={}, groupId={}",
                 owner, matchCollection, groupId);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);
        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesOutsideGroup(collectionId, groupId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("owner/{owner}/matchCollection/{matchCollection}/group/{pGroupId}/matchesWith/{qGroupId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Find matches between the specified groups",
            notes = "Find all matches with one tile in the specified p layer and another tile in the specified q layer.",
            response = CanvasMatches.class,
            responseContainer="List")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Match collection not found")
    })
    public Response getMatchesBetweenGroups(@PathParam("owner") final String owner,
                                            @PathParam("matchCollection") final String matchCollection,
                                            @PathParam("pGroupId") final String pGroupId,
                                            @PathParam("qGroupId") final String qGroupId) {

        LOG.info("getMatchesBetweenGroups: entry, owner={}, matchCollection={}, pGroupId={}, qGroupId={}",
                 owner, matchCollection, pGroupId, qGroupId);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);
        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesBetweenGroups(collectionId, pGroupId, qGroupId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("owner/{owner}/matchCollection/{matchCollection}/group/{pGroupId}/id/{pId}/matchesWith/{qGroupId}/id/{qId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Find matches between the specified objects",
            notes = "Find all matches between two specific tiles.",
            response = CanvasMatches.class,
            responseContainer="List")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Match collection not found")
    })
    public Response getMatchesBetweenObjects(@PathParam("owner") final String owner,
                                             @PathParam("matchCollection") final String matchCollection,
                                             @PathParam("pGroupId") final String pGroupId,
                                             @PathParam("pId") final String pId,
                                             @PathParam("qGroupId") final String qGroupId,
                                             @PathParam("qId") final String qId) {

        LOG.info("getMatchesBetweenObjects: entry, owner={}, matchCollection={}, pGroupId={}, pId={}, qGroupId={}, qId={}",
                 owner, matchCollection, pGroupId, pId, qGroupId, qId);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);
        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesBetweenObjects(collectionId, pGroupId, pId, qGroupId, qId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("owner/{owner}/matchCollection/{matchCollection}/group/{groupId}/matchesOutsideGroup")
    @DELETE
    @ApiOperation(
            value = "Delete matches outside the specified group",
            notes = "Delete all matches with one tile in the specified layer and another tile outside that layer.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Match collection not found")
    })
    public Response deleteMatchesOutsideGroup(@PathParam("owner") final String owner,
                                              @PathParam("matchCollection") final String matchCollection,
                                              @PathParam("groupId") final String groupId) {

        LOG.info("deleteMatchesOutsideGroup: entry, owner={}, matchCollection={}, groupId={}",
                 owner, matchCollection, groupId);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);
        Response response = null;
        try {
            matchDao.removeMatchesOutsideGroup(collectionId, groupId);
            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
     }

    @Path("owner/{owner}/matchCollection/{matchCollection}/matches")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Save a set of matches",
            notes = "Inserts or updates matches for the specified collection.")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "matches successfully saved"),
            @ApiResponse(code = 400, message = "If no matches are provided")
    })
    public Response saveMatches(@PathParam("owner") final String owner,
                                @PathParam("matchCollection") final String matchCollection,
                                @Context final UriInfo uriInfo,
                                final List<CanvasMatches> canvasMatchesList) {

        LOG.info("saveMatches: entry, owner={}, matchCollection={}",
                 owner, matchCollection);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);

        if (canvasMatchesList == null) {
            throw new IllegalServiceArgumentException("no matches provided");
        }

        try {
            matchDao.saveMatches(collectionId, canvasMatchesList);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        LOG.info("saveMatches: exit");

        return responseBuilder.build();
    }

    @Path("owner/{owner}/matchCollection/{matchCollection}/matches")
    @DELETE
    @ApiOperation(
            value = "Delete all matches in the collection",
            notes = "Use this wisely.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Match collection not found")
    })
    public Response deleteAllMatches(@PathParam("owner") final String owner,
                                     @PathParam("matchCollection") final String matchCollection) {

        LOG.info("deleteAllMatches: entry, owner={}, matchCollection={}",
                 owner, matchCollection);

        final MatchCollectionId collectionId = getCollectionId(owner, matchCollection);
        Response response = null;
        try {
            matchDao.removeAllMatches(collectionId);
            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    private MatchCollectionId getCollectionId(final String owner,
                                              final String matchCollection) {

        MatchCollectionId collectionId = null;
        try {
            collectionId = new MatchCollectionId(owner, matchCollection);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return collectionId;
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
