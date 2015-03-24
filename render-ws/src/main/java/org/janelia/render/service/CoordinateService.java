package org.janelia.render.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.service.dao.RenderDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APIs for translating coordinates.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class CoordinateService {

    private final RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public CoordinateService()
            throws UnknownHostException {
        this(RenderServiceUtil.buildDao());
    }

    public CoordinateService(final RenderDao renderDao) {
        this.renderDao = renderDao;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/tileIdsForCoordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTileIdsForCoordinates(@PathParam("owner") final String owner,
                                             @PathParam("project") final String project,
                                             @PathParam("stack") final String stack,
                                             @PathParam("z") final Double z,
                                             final List<TileCoordinates> worldCoordinatesList) {

        LOG.info("getTileIdsForCoordinates: entry, owner={}, project={}, stack={}, z={}, worldCoordinatesList.size()={}",
                 owner, project, stack, z, worldCoordinatesList.size());

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final StreamingOutput responseOutput = new StreamingOutput() {
                @Override
                public void write(final OutputStream output)
                        throws IOException, WebApplicationException {
                    renderDao.writeCoordinatesWithTileIds(stackId, z, worldCoordinatesList, output);
                }
            };
            response = Response.ok(responseOutput).build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("local-to-world-coordinates/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public double[] getWorldCoordinates(@PathParam("x") final double x,
                                       @PathParam("y") final double y,
                                       final TileSpec tileSpec) {

        double[] worldCoordinates = null;
        try {
            worldCoordinates = tileSpec.getWorldCoordinates(x, y);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return worldCoordinates;
    }

    @Path("world-to-local-coordinates/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public double[] getLocalCoordinates(@PathParam("x") final double x,
                                       @PathParam("y") final double y,
                                       @QueryParam("meshCellSize") final Double meshCellSize,
                                       final TileSpec tileSpec) {

        double[] localCoordinates = null;
        try {
            localCoordinates = tileSpec.getLocalCoordinates(x, y, meshCellSize == null ? RenderParameters.DEFAULT_MESH_CELL_SIZE : meshCellSize );
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return localCoordinates;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/local-to-world-coordinates/{x},{y}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TileCoordinates getWorldCoordinates(@PathParam("owner") final String owner,
                                               @PathParam("project") final String project,
                                               @PathParam("stack") final String stack,
                                               @PathParam("tileId") final String tileId,
                                               @PathParam("x") final Double localX,
                                               @PathParam("y") final Double localY) {

        LOG.info("getWorldCoordinates: entry, owner={}, project={}, stack={}, tileId={}, localX={}, localY={}",
                 owner, project, stack, tileId, localX, localY);

        TileCoordinates worldCoordinates = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final TileSpec tileSpec = renderDao.getTileSpec(stackId, tileId, true);
            worldCoordinates = TileCoordinates.getWorldCoordinates(tileSpec, localX.doubleValue(), localY.doubleValue());
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return worldCoordinates;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/local-to-world-coordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileCoordinates> getWorldCoordinates(@PathParam("owner") final String owner,
                                                     @PathParam("project") final String project,
                                                     @PathParam("stack") final String stack,
                                                     @PathParam("z") final Double z,
                                                     final List<TileCoordinates> localCoordinatesList) {

        LOG.info("getWorldCoordinates: entry, owner={}, project={}, stack={}, z={}, localCoordinatesList.size()={}",
                 owner, project, stack, z, localCoordinatesList.size());

        final long startTime = System.currentTimeMillis();
        long lastStatusTime = startTime;
        final List<TileCoordinates> worldCoordinatesList = new ArrayList<>(localCoordinatesList.size());
        final StackId stackId = new StackId(owner, project, stack);
        TileSpec tileSpec;
        TileCoordinates coordinates;
        String tileId;
        double[] local;
        int errorCount = 0;
        for (int i = 0; i < localCoordinatesList.size(); i++) {

            coordinates = localCoordinatesList.get(i);
            try {

                if (coordinates == null) {
                    throw new IllegalArgumentException("coordinates are missing");
                }

                tileId = coordinates.getTileId();
                if (tileId == null) {
                    throw new IllegalArgumentException("tileId is missing");
                }

                local = coordinates.getLocal();
                if (local == null) {
                    throw new IllegalArgumentException("local values are missing");
                } else if (local.length < 2) {
                    throw new IllegalArgumentException("local values must include both x and y");
                }

                tileSpec = renderDao.getTileSpec(stackId, tileId, true);
                worldCoordinatesList.add(TileCoordinates.getWorldCoordinates(tileSpec, local[0], local[1]));

            } catch (final Throwable t) {

                LOG.warn("getWorldCoordinates: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildLocalInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                worldCoordinatesList.add(coordinates);
            }

            if ((System.currentTimeMillis() - lastStatusTime) > COORDINATE_PROCESSING_LOG_INTERVAL) {
                lastStatusTime = System.currentTimeMillis();
                LOG.info("getWorldCoordinates: transformed {} out of {} points",
                         worldCoordinatesList.size(), localCoordinatesList.size());
            }

        }

        LOG.info("getWorldCoordinates: exit, transformed {} points with {} errors in {} ms",
                 worldCoordinatesList.size(), errorCount, (System.currentTimeMillis() - startTime));

        return worldCoordinatesList;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates/{x},{y}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileCoordinates> getLocalCoordinates(@PathParam("owner") final String owner,
                                                     @PathParam("project") final String project,
                                                     @PathParam("stack") final String stack,
                                                     @PathParam("x") final Double worldX,
                                                     @PathParam("y") final Double worldY,
                                                     @PathParam("z") final Double z) {

        LOG.info("getLocalCoordinates: entry, owner={}, project={}, stack={}, worldX={}, worldY={}, z={}",
                 owner, project, stack, worldX, worldY, z);

        List<TileCoordinates> localCoordinatesList = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final List<TileSpec> tileSpecList = renderDao.getTileSpecs(stackId, worldX, worldY, z);
            localCoordinatesList = TileCoordinates.getLocalCoordinates(tileSpecList,
                                                                       worldX.doubleValue(),
                                                                       worldY.doubleValue());
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return localCoordinatesList;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<List<TileCoordinates>> getLocalCoordinates(@PathParam("owner") final String owner,
                                                           @PathParam("project") final String project,
                                                           @PathParam("stack") final String stack,
                                                           @PathParam("z") final Double z,
                                                           final List<TileCoordinates> worldCoordinatesList) {

        LOG.info("getLocalCoordinates: entry, owner={}, project={}, stack={}, z={}, worldCoordinatesList.size()={}",
                 owner, project, stack, z, worldCoordinatesList.size());

        final long startTime = System.currentTimeMillis();
        long lastStatusTime = startTime;
        final List<List<TileCoordinates>> localCoordinatesList = new ArrayList<>(worldCoordinatesList.size());
        final StackId stackId = new StackId(owner, project, stack);
        List<TileSpec> tileSpecList;
        TileCoordinates coordinates;
        double[] world;
        int errorCount = 0;
        for (int i = 0; i < worldCoordinatesList.size(); i++) {

            coordinates = worldCoordinatesList.get(i);
            try {

                if (coordinates == null) {
                    throw new IllegalArgumentException("coordinates are missing");
                }

                world = coordinates.getWorld();
                if (world == null) {
                    throw new IllegalArgumentException("world values are missing");
                } else if (world.length < 2) {
                    throw new IllegalArgumentException("world values must include both x and y");
                }

                tileSpecList = renderDao.getTileSpecs(stackId, (double) world[0], (double) world[1], z);
                localCoordinatesList.add(TileCoordinates.getLocalCoordinates(tileSpecList,
                                                                             world[0],
                                                                             world[1]));

            } catch (final Throwable t) {

                LOG.warn("getLocalCoordinates: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildWorldInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                localCoordinatesList.add(Arrays.asList(coordinates));
            }

            if ((System.currentTimeMillis() - lastStatusTime) > COORDINATE_PROCESSING_LOG_INTERVAL) {
                lastStatusTime = System.currentTimeMillis();
                LOG.info("getLocalCoordinates: inversely transformed {} out of {} points",
                         localCoordinatesList.size(), worldCoordinatesList.size());
            }

        }

        LOG.info("getLocalCoordinates: inversely transformed {} points with {} errors in {} ms",
                 localCoordinatesList.size(), errorCount, (System.currentTimeMillis() - startTime));

        return localCoordinatesList;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CoordinateService.class);

    private static final long COORDINATE_PROCESSING_LOG_INTERVAL = 5000;
}
