package org.janelia.render.service;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.warp.MovingLeastSquaresBuilder;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.MovingLeastSquaresDerivationData;
import org.janelia.render.service.model.stack.StackId;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APIs for working with Moving Least Squares stacks.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class MovingLeastSquaresService {

    private final RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public MovingLeastSquaresService()
            throws UnknownHostException {
        this(RenderServiceUtil.buildDao());
    }

    public MovingLeastSquaresService(final RenderDao renderDao) {
        this.renderDao = renderDao;
    }

    @Path("project/{project}/stack/{alignStack}/z/{z}/movingLeastSquaresTransformUsingMontage/{montageStack}/withAlpha/{alpha}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TransformSpec getMovingLeastSquaresTransform(@PathParam("owner") final String owner,
                                                        @PathParam("project") final String project,
                                                        @PathParam("alignStack") final String alignStack,
                                                        @PathParam("z") final Double z,
                                                        @PathParam("montageStack") final String montageStack,
                                                        @PathParam("alpha") final Double alpha) {

        LOG.info("getMovingLeastSquaresTransform: entry, owner={}, project={}, alignStack={}, z={}, montageStack={}",
                 owner, project, alignStack, z, montageStack);

        TransformSpec transformSpec = null;
        try {
            final StackId alignStackId = new StackId(owner, project, alignStack);
            final StackId montageStackId = new StackId(owner, project, montageStack);

            final List<TileSpec> montageTiles = renderDao.getTileSpecs(montageStackId, z);
            final List<TileSpec> alignTiles = renderDao.getTileSpecs(alignStackId, z);

            transformSpec = buildMovingLeastSquaresTransform(montageTiles, alignTiles, alpha, z);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return transformSpec;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/movingLeastSquaresTiles")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveMovingLeastSquaresTilesForLayer(@PathParam("owner") final String owner,
                                                        @PathParam("project") final String project,
                                                        @PathParam("stack") final String stack,
                                                        @PathParam("z") final Double z,
                                                        @Context final UriInfo uriInfo,
                                                        final MovingLeastSquaresDerivationData derivationData) {

        LOG.info("saveMovingLeastSquaresTilesForLayer: entry, owner={}, project={}, stack={}, z={}, derivationData={}",
                 owner, project, stack, z, derivationData);

        try {
            final StackId mlsStackId = new StackId(owner, project, stack);
            final StackId alignStackId = new StackId(owner, project, derivationData.getAlignStack());
            final StackId montageStackId = new StackId(owner, project, derivationData.getMontageStack());

            final ResolvedTileSpecCollection montageTiles = renderDao.getResolvedTiles(montageStackId, z);
            final ResolvedTileSpecCollection alignTiles = renderDao.getResolvedTiles(alignStackId, z);

            final TransformSpec mlsTransformSpec = buildMovingLeastSquaresTransform(montageTiles.getTileSpecs(),
                                                                                    alignTiles.getTileSpecs(),
                                                                                    derivationData.getAlpha(),
                                                                                    z);

            LOG.info("derived moving least squares transform for layer {}", z);

            montageTiles.addTransformSpecToCollection(mlsTransformSpec);
            montageTiles.addReferenceTransformToAllTiles(mlsTransformSpec.getId());

            LOG.info("derived bounding boxes for layer {}", z);

            renderDao.saveResolvedTiles(mlsStackId, montageTiles);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        LOG.info("upserted tile specs for layer {}", z);

        final URI requestUri = uriInfo.getRequestUri();
        final String requestPath = requestUri.getPath();
        final String tilePath = requestPath.replace("movingLeastSquaresTiles", "tileBounds");
        final UriBuilder uriBuilder = UriBuilder.fromUri(requestUri);
        uriBuilder.replacePath(tilePath);
        final URI tileUri = uriBuilder.build();
        final Response.ResponseBuilder responseBuilder = Response.created(tileUri);

        LOG.info("saveMovingLeastSquaresTilesForLayer: exit");

        return responseBuilder.build();
    }

    private TransformSpec buildMovingLeastSquaresTransform(final Collection<TileSpec> montageTiles,
                                                           final Collection<TileSpec> alignTiles,
                                                           final Double alpha,
                                                           final Double z)
            throws Exception {

        final MovingLeastSquaresBuilder mlsBuilder = new MovingLeastSquaresBuilder(montageTiles, alignTiles, alpha);
        final MovingLeastSquaresTransform2 transform = mlsBuilder.call();
        final String transformId = z + "_MLS";

        return new LeafTransformSpec(transformId,
                                     null,
                                     transform.getClass().getName(),
                                     transform.toDataString());
    }

    private static final Logger LOG = LoggerFactory.getLogger(MovingLeastSquaresService.class);
}
