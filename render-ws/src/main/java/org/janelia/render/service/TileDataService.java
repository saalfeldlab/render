package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.BeanParam;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpecMetaData;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.model.RenderQueryParameters;
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
@Path("/")
@Api(tags = {"Tile Data APIs"})
public class TileDataService {

    private final RenderDao renderDao;
    private final RenderDataService renderDataService;

    @SuppressWarnings({"UnusedDeclaration", "WeakerAccess"})
    public TileDataService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    private TileDataService(final RenderDao renderDao) {
        this.renderDao = renderDao;
        this.renderDataService = new RenderDataService(renderDao);
    }


    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}")
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


    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}")
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

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/render-parameters")
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
                                                @BeanParam final RenderQueryParameters renderQueryParameters,
                                                @QueryParam("width") final Integer width,// full scale width
                                                @QueryParam("height") final Integer height,// full scale height
                                                @QueryParam("normalizeForMatching") final Boolean normalizeForMatching,
                                                @QueryParam("excludeTransformsAfterLast") final Set<String> excludeAfterLastLabels,
                                                @QueryParam("excludeFirstTransformAndAllAfter") final Set<String> excludeFirstAndAllAfterLabels,
                                                @QueryParam("excludeAllTransforms") final Boolean excludeAllTransforms) {

        LOG.info("getRenderParameters: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        RenderParameters parameters = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, true);

            parameters = getCoreTileRenderParameters(width, height, renderQueryParameters.getScale(),
                                                     normalizeForMatching, excludeAfterLastLabels,
                                                     excludeFirstAndAllAfterLabels, excludeAllTransforms, tileSpec);

            renderQueryParameters.applyQueryAndDefaultParameters(parameters,
                                                                 stackMetaData,
                                                                 renderDataService);


        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return parameters;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/validation-info")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(
            value = "Get information indicating whether a tile exists and is valid")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "tile exists and is valid"),
            @ApiResponse(code = 400, message = "tile not valid"),
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public Response getValidationInfo(@PathParam("owner") final String owner,
                                      @PathParam("project") final String project,
                                      @PathParam("stack") final String stack,
                                      @PathParam("tileId") final String tileId) {

        LOG.info("getValidationInfo: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, true);
            tileSpec.validate();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/source/scale/{scale}/render-parameters")
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
                                                          @BeanParam final RenderQueryParameters renderQueryParameters) {

        LOG.info("getTileSourceRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, scale={}",
                 owner, project, stack, tileId, scale);

        return getRawTileRenderParameters(owner, project, stack, tileId, scale, renderQueryParameters, true);
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/mask/scale/{scale}/render-parameters")
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
                                                        @BeanParam final RenderQueryParameters renderQueryParameters) {

        LOG.info("getTileMaskRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, scale={}",
                 owner, project, stack, tileId, scale);

        return getRawTileRenderParameters(owner, project, stack, tileId, scale, renderQueryParameters, false);
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile/{tileId}/withNeighbors/render-parameters")
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
                                                                 @BeanParam final RenderQueryParameters renderQueryParameters) {

        LOG.info("getTileWithNeighborsRenderParameters: entry, owner={}, project={}, stack={}, tileId={}",
                 owner, project, stack, tileId);

        RenderParameters parameters = null;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, false);

            if (widthFactor == null) {
                widthFactor = 0.3;
            }

            if (heightFactor == null) {
                heightFactor = 0.3;
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
                                                                       null,
                                                                       renderQueryParameters);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return parameters;
    }

    static RenderParameters getCoreTileRenderParameters(final Integer width,
                                                        final Integer height,
                                                        final Double scale,
                                                        final Boolean normalizeForMatching,
                                                        final Set<String> excludeAfterLastLabels,
                                                        final Set<String> excludeFirstAndAllAfterLabels,
                                                        final Boolean excludeAllTransforms,
                                                        final TileSpec tileSpec) {

        // Flatten and (if requested) normalize the tile's list of transforms for rendering.
        // Normalization is typically achieved by removing all non-lens correction transformations.

        final boolean useLabelNormalization =
                (excludeAllTransforms != null) ||
                ((excludeAfterLastLabels != null) && (excludeAfterLastLabels.size() > 0)) ||
                ((excludeFirstAndAllAfterLabels != null) && (excludeFirstAndAllAfterLabels.size() > 0));

        final boolean useLegacyNormalization = (normalizeForMatching != null) && normalizeForMatching;

        if (useLabelNormalization) {

            // If the lens correction (or other) transforms have been explicitly labelled,
            // include/exclude transformations with specified labels.
            tileSpec.flattenAndFilterTransforms(excludeAllTransforms,
                                                excludeAfterLastLabels,
                                                excludeFirstAndAllAfterLabels);

            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);  // transforms changed, so re-calculate box

        } else if (useLegacyNormalization) {

            if (tileSpec.hasTransformWithLabel(TransformSpecMetaData.LENS_CORRECTION_LABEL)) {

                // Handle a legacy client request for a stack that has labelled transforms ...
                tileSpec.flattenAndFilterTransforms(null, EXCLUDE_AFTER_LENS, null);

            } else {

                // Handle a legacy client request for a stack without labelled transforms ...
                tileSpec.flattenTransforms();

                // Assume the last transform is an affine that positions the tile in the world and remove it.
                tileSpec.removeLastTransformSpec();

                // If the tile still has more than 3 transforms, remove all but the last 3.
                // This assumes that the last 3 transforms are for lens correction.
                while (tileSpec.getTransforms().size() > 3) {
                    tileSpec.removeLastTransformSpec();
                }
            }

            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);  // transforms changed, so re-calculate box

        } else {

            // No normalization requested, so flatten the transform list and move on.
            tileSpec.flattenTransforms();

        }

        double tileRenderX = tileSpec.getMinX();
        double tileRenderY = tileSpec.getMinY();

        // The legacy approach assumed the tile would be at (0,0) when only lens correction transforms were applied.
        // If we're using the legacy approach, override the upper left of the derived box just in case it moves
        // the tile slightly off the origin.
        if (useLegacyNormalization) {
            tileRenderX = 0.0;
            tileRenderY = 0.0;
        }

        final int tileRenderWidth;
        if (width == null) {
            tileRenderWidth = (int) (tileSpec.getMaxX() - tileSpec.getMinX() + 1);
        } else {
            tileRenderWidth = width;
        }

        final int tileRenderHeight;
        if (height == null) {
            tileRenderHeight = (int) (tileSpec.getMaxY() - tileSpec.getMinY() + 1);
        } else {
            tileRenderHeight = height;
        }

        final RenderParameters parameters =
                new RenderParameters(null, tileRenderX, tileRenderY, tileRenderWidth, tileRenderHeight, scale);
        parameters.addTileSpec(tileSpec);

        return parameters;
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
                                                        final RenderQueryParameters renderQueryParameters,
                                                        final boolean isSource) {

        // we only need to fetch the tile spec since no transforms are needed
        final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId);

        final RenderParameters tileRenderParameters =
                new RenderParameters(null, 0, 0, tileSpec.getWidth(), tileSpec.getHeight(), scale);

        final Map.Entry<Integer, ImageAndMask> firstEntry = tileSpec.getFirstMipmapEntry();
        final ImageAndMask imageAndMask = firstEntry.getValue();
        final TileSpec simpleTileSpec = new TileSpec();
        final ChannelSpec channelSpec = new ChannelSpec();
        simpleTileSpec.addChannel(channelSpec);
        if (isSource) {
            final ImageAndMask imageWithoutMask = new ImageAndMask(imageAndMask.getImageUrl(), null);
            channelSpec.putMipmap(firstEntry.getKey(), imageWithoutMask);
        } else {
            final ImageAndMask maskAsImage = new ImageAndMask(imageAndMask.getMaskUrl(), null);
            channelSpec.putMipmap(firstEntry.getKey(), maskAsImage);
        }
        tileRenderParameters.addTileSpec(simpleTileSpec);
        renderQueryParameters.applyQueryAndDefaultParameters(tileRenderParameters, null, renderDataService);

        // since we have only one tile with no transformations,
        // skip interpolation to save pixels in last row and column of image
        tileRenderParameters.setSkipInterpolation(true);

        return tileRenderParameters;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileDataService.class);

    private static final Set<String> EXCLUDE_AFTER_LENS =
            Collections.singleton(TransformSpecMetaData.LENS_CORRECTION_LABEL);
}
