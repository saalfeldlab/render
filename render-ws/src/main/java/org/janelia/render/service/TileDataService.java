package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

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

    @SuppressWarnings("UnusedDeclaration")
    public TileDataService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    public TileDataService(final RenderDao renderDao) {
        this.renderDao = renderDao;
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

    @Path("project/{project}/stack/{stack}/tile/{tileId}/render-parameters")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get parameters for rendering 'uniform' tile",
            notes = "The returned x, y, width, and height parameters are uniform for all tiles in the stack.")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "tile not found"),
    })
    public RenderParameters getRenderParameters(@PathParam("owner") final String owner,
                                                @PathParam("project") final String project,
                                                @PathParam("stack") final String stack,
                                                @PathParam("tileId") final String tileId,
                                                @QueryParam("scale") Double scale,
                                                @QueryParam("filter") final Boolean filter,
                                                @QueryParam("binaryMask") final Boolean binaryMask) {

        LOG.info("getRenderParameters: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}, binaryMask={}",
                 owner, project, stack, tileId, scale, filter, binaryMask);

        RenderParameters parameters = null;
        try {
            final TileSpec tileSpec = getTileSpec(owner, project, stack, tileId, true);
            tileSpec.flattenTransforms();
            if (scale == null) {
                scale = 1.0;
            }

            final StackId stackId = new StackId(owner, project, stack);
            final StackMetaData stackMetaData = getStackMetaData(stackId);

            final Integer stackLayoutWidth = stackMetaData.getLayoutWidth();
            final Integer stackLayoutHeight = stackMetaData.getLayoutHeight();

            final int margin = 0;
            final Double x = getLayoutMinValue(tileSpec.getMinX(), margin);
            final Double y = getLayoutMinValue(tileSpec.getMinY(), margin);
            final Integer width = getLayoutSizeValue(stackLayoutWidth, tileSpec.getWidth(), margin);
            final Integer height = getLayoutSizeValue(stackLayoutHeight, tileSpec.getHeight(), margin);

            parameters = new RenderParameters(null, x, y, width, height, scale);
            parameters.setDoFilter(filter);
            parameters.setBinaryMask(binaryMask);
            parameters.addTileSpec(tileSpec);
            parameters.setMipmapPathBuilder(stackMetaData.getCurrentMipmapPathBuilder());

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

        return getTileRenderParameters(owner, project, stack, tileId, scale, filter, true);
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

        return getTileRenderParameters(owner, project, stack, tileId, scale, filter, false);
    }

    public StackMetaData getStackMetaData(final StackId stackId)
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

    private RenderParameters getTileRenderParameters(final String owner,
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

    private Double getLayoutMinValue(final Double minValue,
                                     final int margin) {
        Double layoutValue = null;
        if (minValue != null) {
            layoutValue = minValue - margin;
        }
        return layoutValue;
    }

    private Integer getLayoutSizeValue(final Integer stackValue,
                                       final Integer tileValue,
                                       final int margin) {
        Integer layoutValue = null;

        if (stackValue != null) {
            layoutValue = stackValue + margin;
        } else if ((tileValue != null) && (tileValue >= 0)) {
            layoutValue = tileValue + margin;
        }

        if ((layoutValue != null) && ((layoutValue % 2) != 0)) {
            layoutValue = layoutValue + 1;
        }

        return layoutValue;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileDataService.class);
}
