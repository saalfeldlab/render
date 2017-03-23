package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.Map;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * Tile centric APIs for accessing data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
@Api(tags = {"Tile Data APIs"})
public class TileDataService {

    private final RenderDao renderDao;
    private final RenderDataService renderDataService;

    @SuppressWarnings("UnusedDeclaration")
    public TileDataService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    public TileDataService(final RenderDao renderDao) {
        this.renderDao = renderDao;
        this.renderDataService = new RenderDataService(renderDao);
    }


    @Path("project/{project}/stack/{stack}/tile/{tileId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get tile spec")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public TileSpec getTileSpec(@PathParam("owner") final String owner,
                                @PathParam("project") final String project,
                                @PathParam("stack") final String stack,
                                @PathParam("tileId") final String tileId) {

        LOG.info("getTileSpec: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        TileSpec tileSpec = null;
        try {
            tileSpec = getTileSpec(owner, project, stack, tileId, false);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return tileSpec;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}")
    @DELETE
    @ApiOperation(
            value = "Deletes specified tile.",
            notes = "This operation can only be performed against stacks in the LOADING state")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack state is not LOADING"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response deleteTile(@PathParam("owner") final String owner,
                               @PathParam("project") final String project,
                               @PathParam("stack") final String stack,
                               @PathParam("tileId") final String tileId) {

        LOG.info("deleteTile: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            if (! stackMetaData.isLoading()) {
                throw new IllegalArgumentException("stack state is " + stackMetaData.getState() +
                                                   " but must be LOADING to delete data");
            }

            renderDao.removeTile(stackMetaData.getStackId(), tileId);

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }



                                                }
    @Path("project/{project}/stack/{stack}/tile/{tileId}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get parameters for rendering the tile")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public RenderParameters getRenderParameters(@PathParam("owner") final String owner,
                                                @PathParam("project") final String project,
                                                @PathParam("stack") final String stack,
                                                @PathParam("tileId") final String tileId,
                                                @QueryParam("width") final Integer width,   // full scale width
                                                @QueryParam("height") final Integer height, // full scale height
                                                @QueryParam("scale") final Double scale,
                                                @QueryParam("filter") final Boolean filter,
                                                @QueryParam("binaryMask") final Boolean binaryMask,
                                                @QueryParam("excludeMask") final Boolean excludeMask,
                                                @QueryParam("normalizeForMatching") final Boolean normalizeForMatching,
                                                @QueryParam("minIntensity") final Double minIntensity,
                                                @QueryParam("maxIntensity") final Double maxIntensity,
                                                @QueryParam("removeAllOption") final Boolean removeAllOption) {

        LOG.info("getRenderParameters: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        RenderParameters parameters = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, true);

            parameters = getCoreTileRenderParameters(width, height, scale, normalizeForMatching, tileSpec, removeAllOption);

            parameters.setDoFilter(filter);
            parameters.setBinaryMask(binaryMask);
            parameters.setExcludeMask(excludeMask);
            parameters.addTileSpec(tileSpec);
            parameters.setMipmapPathBuilder(stackMetaData.getCurrentMipmapPathBuilder());
            parameters.setMinIntensity(minIntensity);
            parameters.setMaxIntensity(maxIntensity);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return parameters;
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/source/scale/{scale}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get parameters for rendering tile source image (without transformations or mask)")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public RenderParameters getTileSourceRenderParameters(@PathParam("owner") final String owner,
                                                          @PathParam("project") final String project,
                                                          @PathParam("stack") final String stack,
                                                          @PathParam("tileId") final String tileId,
                                                          @PathParam("scale") final Double scale,
                                                          @QueryParam("filter") final Boolean filter) {

        LOG.info("getTileSourceRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        return getRawTileRenderParameters(owner, project, stack, tileId, scale, filter, true);
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/mask/scale/{scale}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get parameters for rendering tile mask image (without transformations)")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public RenderParameters getTileMaskRenderParameters(@PathParam("owner") final String owner,
                                                        @PathParam("project") final String project,
                                                        @PathParam("stack") final String stack,
                                                        @PathParam("tileId") final String tileId,
                                                        @PathParam("scale") final Double scale,
                                                        @QueryParam("filter") final Boolean filter) {

        LOG.info("getTileMaskRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        return getRawTileRenderParameters(owner, project, stack, tileId, scale, filter, false);
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/withNeighbors/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get parameters for rendering a tile with its neighbors")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public RenderParameters getTileWithNeighborsRenderParameters(@PathParam("owner") final String owner,
                                                                 @PathParam("project") final String project,
                                                                 @PathParam("stack") final String stack,
                                                                 @PathParam("tileId") final String tileId,
                                                                 @QueryParam("widthFactor") Double widthFactor,
                                                                 @QueryParam("heightFactor") Double heightFactor,
                                                                 @QueryParam("scale") Double scale,
                                                                 @QueryParam("filter") final Boolean filter,
                                                                 @QueryParam("binaryMask") final Boolean binaryMask,
                                                                 @QueryParam("convertToGray") final Boolean convertToGray) {

        LOG.info("getTileWithNeighborsRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, widthFactor={}, heightFactor={}, scale={}, filter={}, binaryMask={}, convertToGray={}",
                 owner, project, stack, tileId, widthFactor, heightFactor, scale, filter, binaryMask, convertToGray);

        RenderParameters parameters = null;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);

            if (widthFactor == null) {
                widthFactor = 0.3;
            }

            if (heightFactor == null) {
                heightFactor = 0.3;
            }

            if (scale == null) {
                scale = 1.0;
            }

            final double neighborhoodWidth = tileSpec.getWidth() * widthFactor;
            final double neighborhoodHeight = tileSpec.getHeight() * heightFactor;

            final double x = tileSpec.getMinX() - neighborhoodWidth;
            final double y = tileSpec.getMinY() - neighborhoodHeight;
            final double z = tileSpec.getZ();
            final int width = tileSpec.getWidth() + (int) (2 * neighborhoodWidth);
            final int height = tileSpec.getHeight() + (int) (2 * neighborhoodHeight);

            parameters = renderDataService.getExternalRenderParameters(owner,
                                                                       project,
                                                                       stack,
                                                                       x,
                                                                       y,
                                                                       z,
                                                                       width,
                                                                       height,
                                                                       scale,
                                                                       filter,
                                                                       binaryMask,
                                                                       convertToGray);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return parameters;
    }
    protected static RenderParameters getCoreTileRenderParameters(final Integer width,
                                                                  final Integer height,
                                                                  final Double scale,
                                                                  final Boolean normalizeForMatching,
                                                                  final TileSpec tileSpec){
       return getCoreTileRenderParameters(width,height,scale,normalizeForMatching,tileSpec,false);
                                                                  }

    protected static RenderParameters getCoreTileRenderParameters(final Integer width,
                                                                  final Integer height,
                                                                  final Double scale,
                                                                  final Boolean normalizeForMatching,
                                                                  final TileSpec tileSpec,
                                                                  final Boolean removeAllOption) {
        tileSpec.flattenTransforms();

        double tileRenderX = tileSpec.getMinX();
        double tileRenderY = tileSpec.getMinY();

        int tileRenderWidth;
        if (width == null) {
            tileRenderWidth = (int) (tileSpec.getMaxX() - tileSpec.getMinX() + 1);
        } else {
            tileRenderWidth = width;
        }

        int tileRenderHeight;
        if (height == null) {
            tileRenderHeight = (int) (tileSpec.getMaxY() - tileSpec.getMinY() + 1);
        } else {
            tileRenderHeight = height;
        }

        if ((normalizeForMatching != null) && normalizeForMatching) {

            // When deriving point matches for a tile pair in which each tile has different lens correction
            // transformations, the bounding box for each rendered tile needs to be normalized.
            //
            // Normalization is achieved by:
            // (1) Removing the last transform spec (assumed to be an affine that positions the tile in the world).
            // (2) Setting the render start coordinate to (0,0).
            // (3) Padding the raw tile width and height by multiplying a normalization factor (1.05).
            //     Assuming that all tiles in a stack have the same raw width and height, this ensures
            //     that normalized tiles also have the same width and height with a little extra room
            //     for edges that are rotated/skewed by lens correction.
            // (4) Ensuring that the normalized width and height are even by adding a pixel as needed.
            //
            // Consistent and even tile sizes are currently a requirement for generating DMesh point matches.

            final double normalizationFactor = 1.05;
            if (width == null) {
                tileRenderWidth = (int) (tileSpec.getWidth() * normalizationFactor);
            }
            if (height == null) {
                tileRenderHeight = (int) (tileSpec.getHeight() * normalizationFactor);
            }
            if (removeAllOption){
              while (tileSpec.getTransforms().size() > 0) {
                  tileSpec.removeLastTransformSpec();
              }
            }
            else{
              tileSpec.removeLastTransformSpec();

              // If the tile still has more than 3 transforms, remove all but the last 3.
              // This assumes that the last 3 transforms are for lens correction.
              // Hopefully at some point we'll label transforms so that it is possible to
              // explicitly include only lens correction transforms.
              while (tileSpec.getTransforms().size() > 3) {
                  tileSpec.removeLastTransformSpec();
              }
            }

            tileRenderX = 0;
            tileRenderY = 0;

            if ((tileRenderWidth % 2) == 1) {
                tileRenderWidth++;
            }
            if ((tileRenderHeight % 2) == 1) {
                tileRenderHeight++;
            }
        }

        return new RenderParameters(null, tileRenderX, tileRenderY, tileRenderWidth, tileRenderHeight, scale);
    }

    private StackMetaData getStackMetaData(final StackId stackId)
            throws ObjectNotFoundException {

        final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
        if (stackMetaData == null) {
            throw StackMetaDataService.getStackNotFoundException(stackId.getOwner(),
                                                                 stackId.getProject(),
                                                                 stackId.getStack());
        }
        return stackMetaData;
    }

    private TileSpec getTileSpec(final String owner,
                                 final String project,
                                 final String stack,
                                 final String tileId,
                                 final boolean resolveTransformReferences) {
        final StackId stackId = new StackId(owner, project, stack);
        return renderDao.getTileSpec(stackId, tileId, resolveTransformReferences);
    }

    private RenderParameters getRawTileRenderParameters(final String owner,
                                                        final String project,
                                                        final String stack,
                                                        final String tileId,
                                                        final Double scale,
                                                        final Boolean filter,
                                                        final boolean isSource) {

        // we only need to fetch the tile spec since no transforms are needed
        final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId);

        final RenderParameters tileRenderParameters =
                new RenderParameters(null, 0, 0, tileSpec.getWidth(), tileSpec.getHeight(), scale);

        final Map.Entry<Integer, ImageAndMask> firstEntry = tileSpec.getFirstMipmapEntry();
        final ImageAndMask imageAndMask = firstEntry.getValue();
        final TileSpec simpleTileSpec = new TileSpec();
        if (isSource) {
            final ImageAndMask imageWithoutMask = new ImageAndMask(imageAndMask.getImageUrl(), null);
            simpleTileSpec.putMipmap(firstEntry.getKey(), imageWithoutMask);
        } else {
            final ImageAndMask maskAsImage = new ImageAndMask(imageAndMask.getMaskUrl(), null);
            simpleTileSpec.putMipmap(firstEntry.getKey(), maskAsImage);
        }
        tileRenderParameters.addTileSpec(simpleTileSpec);
        tileRenderParameters.setDoFilter(filter);

        return tileRenderParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileDataService.class);
}
