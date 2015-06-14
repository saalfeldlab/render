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

    @Path("matchCollection/{matchCollection}/section/{sectionId}/matchesWithinLayer")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchesWithinLayer(@PathParam("owner") final String owner,
                                          @PathParam("matchCollection") final String matchCollection,
                                          @PathParam("sectionId") final String sectionId) {

        LOG.info("getMatchesWithinLayer: entry, owner={}, matchCollection={}, sectionId={}",
                 owner, matchCollection, sectionId);

        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesWithinLayer(matchCollection, sectionId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("matchCollection/{matchCollection}/section/{sectionId}/matchesOutsideLayer")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMatchesOutsideLayer(@PathParam("owner") final String owner,
                                           @PathParam("matchCollection") final String matchCollection,
                                           @PathParam("sectionId") final String sectionId) {

        LOG.info("getMatchesWithinLayer: entry, owner={}, matchCollection={}, sectionId={}",
                 owner, matchCollection, sectionId);

        final StreamingOutput responseOutput = new StreamingOutput() {
            @Override
            public void write(final OutputStream output)
                    throws IOException, WebApplicationException {
                matchDao.writeMatchesOutsideLayer(matchCollection, sectionId, output);
            }
        };

        return streamResponse(responseOutput);
    }

    @Path("matchCollection/{matchCollection}/section/{sectionId}/matchesOutsideLayer")
    @DELETE
    public Response deleteMatchesOutsideLayer(@PathParam("owner") final String owner,
                                              @PathParam("matchCollection") final String matchCollection,
                                              @PathParam("sectionId") final String sectionId) {

        LOG.info("deleteMatchesOutsideLayer: entry, owner={}, matchCollection={}, sectionId={}",
                 owner, matchCollection, sectionId);

        Response response = null;
        try {
            matchDao.removeMatchesOutsideLayer(matchCollection, sectionId);
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
