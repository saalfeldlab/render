package org.janelia.render.service;

import org.janelia.alignment.spec.TileCoordinates;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.render.service.dao.RenderDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * APIs for translating coordinates.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class CoordinateService {

    private RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public CoordinateService()
            throws UnknownHostException {
        this(RenderServiceUtil.buildDao());
    }

    public CoordinateService(RenderDao renderDao) {
        this.renderDao = renderDao;
    }

    @Path("local-to-world-coordinates/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public float[] getWorldCoordinates(@PathParam("x") float x,
                                       @PathParam("y") float y,
                                       TileSpec tileSpec) {

        float[] worldCoordinates = null;
        try {
            worldCoordinates = tileSpec.getWorldCoordinates(x, y);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return worldCoordinates;
    }

    @Path("world-to-local-coordinates/{x},{y}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public float[] getLocalCoordinates(@PathParam("x") float x,
                                       @PathParam("y") float y,
                                       TileSpec tileSpec) {

        float[] localCoordinates = null;
        try {
            localCoordinates = tileSpec.getLocalCoordinates(x, y);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return localCoordinates;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/local-to-world-coordinates/{x},{y}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TileCoordinates getWorldCoordinates(@PathParam("owner") String owner,
                                               @PathParam("project") String project,
                                               @PathParam("stack") String stack,
                                               @PathParam("tileId") String tileId,
                                               @PathParam("x") Double localX,
                                               @PathParam("y") Double localY) {

        LOG.info("getWorldCoordinates: entry, owner={}, project={}, stack={}, tileId={}, localX={}, localY={}",
                 owner, project, stack, tileId, localX, localY);

        TileCoordinates worldCoordinates = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final TileSpec tileSpec = renderDao.getTileSpec(stackId, tileId, true);
            worldCoordinates = TileCoordinates.getWorldCoordinates(tileSpec, localX.floatValue(), localY.floatValue());
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return worldCoordinates;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/local-to-world-coordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileCoordinates> getWorldCoordinates(@PathParam("owner") String owner,
                                                     @PathParam("project") String project,
                                                     @PathParam("stack") String stack,
                                                     @PathParam("z") Double z,
                                                     List<TileCoordinates> localCoordinatesList) {

        LOG.info("getWorldCoordinates: entry, owner={}, project={}, stack={}, z={}, localCoordinatesList.size()={}",
                 owner, project, stack, z, localCoordinatesList.size());

        final long startTime = System.currentTimeMillis();
        long lastStatusTime = startTime;
        List<TileCoordinates> worldCoordinatesList = new ArrayList<TileCoordinates>(localCoordinatesList.size());
        final StackId stackId = new StackId(owner, project, stack);
        TileSpec tileSpec;
        TileCoordinates coordinates;
        String tileId;
        float[] local;
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

            } catch (Throwable t) {

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
    public TileCoordinates getLocalCoordinates(@PathParam("owner") String owner,
                                               @PathParam("project") String project,
                                               @PathParam("stack") String stack,
                                               @PathParam("x") Double worldX,
                                               @PathParam("y") Double worldY,
                                               @PathParam("z") Double z) {

        LOG.info("getLocalCoordinates: entry, owner={}, project={}, stack={}, worldX={}, worldY={}, z={}",
                 owner, project, stack, worldX, worldY, z);

        TileCoordinates localCoordinates = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final TileSpec tileSpec = renderDao.getTileSpec(stackId, worldX, worldY, z);
            localCoordinates = TileCoordinates.getLocalCoordinates(tileSpec, worldX.floatValue(), worldY.floatValue());
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return localCoordinates;
    }

    @Path("project/{project}/stack/{stack}/z/{z}/world-to-local-coordinates")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<TileCoordinates> getLocalCoordinates(@PathParam("owner") String owner,
                                                     @PathParam("project") String project,
                                                     @PathParam("stack") String stack,
                                                     @PathParam("z") Double z,
                                                     List<TileCoordinates> worldCoordinatesList) {

        LOG.info("getLocalCoordinates: entry, owner={}, project={}, stack={}, z={}, worldCoordinatesList.size()={}",
                 owner, project, stack, z, worldCoordinatesList.size());

        final long startTime = System.currentTimeMillis();
        long lastStatusTime = startTime;
        List<TileCoordinates> localCoordinatesList = new ArrayList<TileCoordinates>(worldCoordinatesList.size());
        final StackId stackId = new StackId(owner, project, stack);
        TileSpec tileSpec;
        TileCoordinates coordinates;
        float[] world;
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

                tileSpec = renderDao.getTileSpec(stackId, (double) world[0], (double) world[1], z);
                localCoordinatesList.add(TileCoordinates.getLocalCoordinates(tileSpec, world[0], world[1]));

            } catch (Throwable t) {

                LOG.warn("getLocalCoordinates: caught exception for list item {}, adding original coordinates with error message to list", i, t);

                errorCount++;

                if (coordinates == null) {
                    coordinates = TileCoordinates.buildWorldInstance(null, null);
                }
                coordinates.setError(t.getMessage());

                localCoordinatesList.add(coordinates);
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
