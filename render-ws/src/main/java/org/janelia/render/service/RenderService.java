package org.janelia.render.service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.UnknownHostException;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.janelia.alignment.BoundingBoxRenderer;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.BufferedImageStreamingOutput;
import org.janelia.render.service.util.RenderServiceUtil;
import org.janelia.render.service.util.SharedImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * APIs that use the {@link Render} tool to render images.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
@Api(tags = {"Render Image APIs"},
     description = "Render Image Service")
public class RenderService {

    private final RenderDataService renderDataService;

    @SuppressWarnings("UnusedDeclaration")
    public RenderService()
            throws UnknownHostException {
        this(new RenderDataService());
    }

    public RenderService(final RenderDataService renderDataService) {
        this.renderDataService = renderDataService;
    }

    @Path("jpeg-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render JPEG image from a provide spec")
    public Response renderJpegImageFromProvidedParameters(final RenderParameters renderParameters) {
        return renderImageStream(renderParameters, Utils.JPEG_FORMAT, IMAGE_JPEG_MIME_TYPE, false, NO_CACHE_HELPER);
    }


    @Path("png-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render PNG image from a provide spec")
    public Response renderPngImageFromProvidedParameters(final RenderParameters renderParameters) {
        return renderImageStream(renderParameters, Utils.PNG_FORMAT, IMAGE_PNG_MIME_TYPE, false, NO_CACHE_HELPER);
    }

    @Path("tiff-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render TIFF image from a provide spec")
    public Response renderTiffImageFromProvidedParameters(final RenderParameters renderParameters) {
        return renderImageStream(renderParameters, Utils.TIFF_FORMAT, IMAGE_TIFF_MIME_TYPE, false, NO_CACHE_HELPER);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render JPEG image for a section")
    public Response renderJpegImageForZ(@PathParam("owner") final String owner,
                                        @PathParam("project") final String project,
                                        @PathParam("stack") final String stack,
                                        @PathParam("z") final Double z,
                                        @QueryParam("scale") Double scale,
                                        @QueryParam("filter") final Boolean filter,
                                        @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                        @Context final Request request) {

        LOG.info("renderJpegImageForZ: entry, owner={}, project={}, stack={}, z={}, scale={}, filter={}",
                 owner, project, stack, z, scale, filter);

        if (scale == null) {
            scale = 0.01;
        }

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParametersForZ(owner, project, stack, z, scale, filter);
            return renderJpegImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render PNG image for a section")
    public Response renderPngImageForZ(@PathParam("owner") final String owner,
                                       @PathParam("project") final String project,
                                       @PathParam("stack") final String stack,
                                       @PathParam("z") final Double z,
                                       @QueryParam("scale") Double scale,
                                       @QueryParam("filter") final Boolean filter,
                                       @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                       @Context final Request request) {

        LOG.info("renderPngImageForZ: entry, owner={}, project={}, stack={}, z={}, scale={}, filter={}",
                 owner, project, stack, z, scale, filter);

        if (scale == null) {
            scale = 0.01;
        }

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParametersForZ(owner, project, stack, z, scale, filter);
            return renderPngImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/tiff-image")
    @GET
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render TIFF image for a section")
    public Response renderTiffImageForZ(@PathParam("owner") final String owner,
                                        @PathParam("project") final String project,
                                        @PathParam("stack") final String stack,
                                        @PathParam("z") final Double z,
                                        @QueryParam("scale") Double scale,
                                        @QueryParam("filter") final Boolean filter,
                                        @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                        @Context final Request request) {

        LOG.info("renderTiffImageForZ: entry, owner={}, project={}, stack={}, z={}, scale={}, filter={}",
                 owner, project, stack, z, scale, filter);

        if (scale == null) {
            scale = 0.01;
        }

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParametersForZ(owner, project, stack, z, scale, filter);
            return renderTiffImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/scale/{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render JPEG image for a tile")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderJpegImageForTile(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack,
                                           @PathParam("tileId") final String tileId,
                                           @PathParam("scale") final Double scale,
                                           @QueryParam("filter") final Boolean filter,
                                           @Context final Request request) {

        LOG.info("renderJpegImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParameters(owner, project, stack, tileId, scale, filter, false);
            return renderJpegImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/scale/{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render PNG image for a tile")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderPngImageForTile(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("tileId") final String tileId,
                                          @PathParam("scale") final Double scale,
                                          @QueryParam("filter") final Boolean filter,
                                          @Context final Request request) {

        LOG.info("renderPngImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParameters(owner, project, stack, tileId, scale, filter, false);
            return renderPngImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/scale/{scale}/tiff-image")
    @GET
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render TIFF image for a tile")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderTiffImageForTile(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack,
                                           @PathParam("tileId") final String tileId,
                                           @PathParam("scale") final Double scale,
                                           @QueryParam("filter") final Boolean filter,
                                           @Context final Request request) {

        LOG.info("renderTiffImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParameters(owner, project, stack, tileId, scale, filter, false);
            return renderTiffImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/source/scale/{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render tile's source image without transformations in JPEG format")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderJpegSourceImageForTile(@PathParam("owner") final String owner,
                                                 @PathParam("project") final String project,
                                                 @PathParam("stack") final String stack,
                                                 @PathParam("tileId") final String tileId,
                                                 @PathParam("scale") final Double scale,
                                                 @QueryParam("filter") final Boolean filter,
                                                 @Context final Request request) {

        LOG.info("renderJpegSourceImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getTileSourceRenderParameters(owner, project, stack, tileId, scale, filter);
            return renderJpegImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/source/scale/{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render tile's source image without transformations in PNG format")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderPngSourceImageForTile(@PathParam("owner") final String owner,
                                                @PathParam("project") final String project,
                                                @PathParam("stack") final String stack,
                                                @PathParam("tileId") final String tileId,
                                                @PathParam("scale") final Double scale,
                                                @QueryParam("filter") final Boolean filter,
                                                @Context final Request request) {

        LOG.info("renderPngSourceImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getTileSourceRenderParameters(owner, project, stack, tileId, scale, filter);
            return renderPngImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/source/scale/{scale}/tiff-image")
    @GET
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render tile's source image without transformations in TIFF format")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderTiffSourceImageForTile(@PathParam("owner") final String owner,
                                                 @PathParam("project") final String project,
                                                 @PathParam("stack") final String stack,
                                                 @PathParam("tileId") final String tileId,
                                                 @PathParam("scale") final Double scale,
                                                 @QueryParam("filter") final Boolean filter,
                                                 @Context final Request request) {

        LOG.info("renderTiffSourceImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getTileSourceRenderParameters(owner, project, stack, tileId, scale, filter);
            return renderTiffImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/mask/scale/{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render tile's mask image without transformations in JPEG format")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderJpegMaskImageForTile(@PathParam("owner") final String owner,
                                               @PathParam("project") final String project,
                                               @PathParam("stack") final String stack,
                                               @PathParam("tileId") final String tileId,
                                               @PathParam("scale") final Double scale,
                                               @QueryParam("filter") final Boolean filter,
                                               @Context final Request request) {

        LOG.info("renderJpegMaskImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getTileMaskRenderParameters(owner, project, stack, tileId, scale, filter);
            return renderJpegImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/mask/scale/{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render tile's mask image without transformations in PNG format")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderPngMaskImageForTile(@PathParam("owner") final String owner,
                                              @PathParam("project") final String project,
                                              @PathParam("stack") final String stack,
                                              @PathParam("tileId") final String tileId,
                                              @PathParam("scale") final Double scale,
                                              @QueryParam("filter") final Boolean filter,
                                              @Context final Request request) {

        LOG.info("renderPngMaskImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getTileMaskRenderParameters(owner, project, stack, tileId, scale, filter);
            return renderPngImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/mask/scale/{scale}/tiff-image")
    @GET
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render tile's mask image without transformations in TIFF format")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Tile not found")
    })
    public Response renderTiffMaskImageForTile(@PathParam("owner") final String owner,
                                               @PathParam("project") final String project,
                                               @PathParam("stack") final String stack,
                                               @PathParam("tileId") final String tileId,
                                               @PathParam("scale") final Double scale,
                                               @QueryParam("filter") final Boolean filter,
                                               @Context final Request request) {

        LOG.info("renderTiffMaskImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getTileMaskRenderParameters(owner, project, stack, tileId, scale, filter);
            return renderTiffImage(renderParameters, false, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render JPEG image for the specified bounding box")
    public Response renderJpegImageForBox(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("x") final Double x,
                                          @PathParam("y") final Double y,
                                          @PathParam("z") final Double z,
                                          @PathParam("width") final Integer width,
                                          @PathParam("height") final Integer height,
                                          @PathParam("scale") final Double scale,
                                          @QueryParam("filter") final Boolean filter,
                                          @QueryParam("binaryMask") final Boolean binaryMask,
                                          @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                          @Context final Request request) {

        LOG.info("renderJpegImageForBox: entry");

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, null,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return renderJpegImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render PNG image for the specified bounding box")
    public Response renderPngImageForBox(@PathParam("owner") final String owner,
                                         @PathParam("project") final String project,
                                         @PathParam("stack") final String stack,
                                         @PathParam("x") final Double x,
                                         @PathParam("y") final Double y,
                                         @PathParam("z") final Double z,
                                         @PathParam("width") final Integer width,
                                         @PathParam("height") final Integer height,
                                         @PathParam("scale") final Double scale,
                                         @QueryParam("filter") final Boolean filter,
                                         @QueryParam("binaryMask") final Boolean binaryMask,
                                         @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                         @Context final Request request) {

        LOG.info("renderPngImageForBox: entry");

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, null,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return renderPngImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/tiff-image")
    @GET
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render TIFF image for the specified bounding box")
    public Response renderTiffImageForBox(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("x") final Double x,
                                          @PathParam("y") final Double y,
                                          @PathParam("z") final Double z,
                                          @PathParam("width") final Integer width,
                                          @PathParam("height") final Integer height,
                                          @PathParam("scale") final Double scale,
                                          @QueryParam("filter") final Boolean filter,
                                          @QueryParam("binaryMask") final Boolean binaryMask,
                                          @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                          @Context final Request request) {

        LOG.info("renderTiffImageForBox: entry");

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, null,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return renderTiffImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(value = "Render JPEG image for the specified bounding box and groupId")
    public Response renderJpegImageForGroupBox(@PathParam("owner") final String owner,
                                               @PathParam("project") final String project,
                                               @PathParam("stack") final String stack,
                                               @PathParam("groupId") final String groupId,
                                               @PathParam("x") final Double x,
                                               @PathParam("y") final Double y,
                                               @PathParam("z") final Double z,
                                               @PathParam("width") final Integer width,
                                               @PathParam("height") final Integer height,
                                               @PathParam("scale") final Double scale,
                                               @QueryParam("filter") final Boolean filter,
                                               @QueryParam("binaryMask") final Boolean binaryMask,
                                               @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                               @Context final Request request) {

        LOG.info("renderJpegImageForGroupBox: entry");

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, groupId,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return renderJpegImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    @ApiOperation(value = "Render PNG image for the specified bounding box and groupId")
    public Response renderPngImageForGroupBox(@PathParam("owner") final String owner,
                                              @PathParam("project") final String project,
                                              @PathParam("stack") final String stack,
                                              @PathParam("groupId") final String groupId,
                                              @PathParam("x") final Double x,
                                              @PathParam("y") final Double y,
                                              @PathParam("z") final Double z,
                                              @PathParam("width") final Integer width,
                                              @PathParam("height") final Integer height,
                                              @PathParam("scale") final Double scale,
                                              @QueryParam("filter") final Boolean filter,
                                              @QueryParam("binaryMask") final Boolean binaryMask,
                                              @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                              @Context final Request request) {

        LOG.info("renderPngImageForGroupBox: entry");

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, groupId,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return renderPngImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/tiff-image")
    @GET
    @Produces(IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(value = "Render TIFF image for the specified bounding box and groupId")
    public Response renderTiffImageForGroupBox(@PathParam("owner") final String owner,
                                               @PathParam("project") final String project,
                                               @PathParam("stack") final String stack,
                                               @PathParam("groupId") final String groupId,
                                               @PathParam("x") final Double x,
                                               @PathParam("y") final Double y,
                                               @PathParam("z") final Double z,
                                               @PathParam("width") final Integer width,
                                               @PathParam("height") final Integer height,
                                               @PathParam("scale") final Double scale,
                                               @QueryParam("filter") final Boolean filter,
                                               @QueryParam("binaryMask") final Boolean binaryMask,
                                               @QueryParam("optimizeRenderTime") final Boolean optimizeRenderTime,
                                               @Context final Request request) {

        LOG.info("renderTiffImageForGroupBox: entry");

        final ResponseHelper responseHelper = new ResponseHelper(owner, project, stack, request, renderDataService);
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, groupId,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return renderTiffImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    private RenderParameters getRenderParametersForGroupBox(final String owner,
                                                            final String project,
                                                            final String stack,
                                                            final String groupId,
                                                            final Double x,
                                                            final Double y,
                                                            final Double z,
                                                            final Integer width,
                                                            final Integer height,
                                                            final Double scale,
                                                            final Boolean filter,
                                                            final Boolean binaryMask) {

        final StackId stackId = new StackId(owner, project, stack);
        final RenderParameters renderParameters = renderDataService.getInternalRenderParameters(stackId,
                                                                                                groupId,
                                                                                                x,
                                                                                                y,
                                                                                                z,
                                                                                                width,
                                                                                                height,
                                                                                                scale);
        renderParameters.setDoFilter(filter);
        renderParameters.setBinaryMask(binaryMask);

        return renderParameters;
    }

    private Response renderJpegImage(final RenderParameters renderParameters,
                                     final Boolean optimizeRenderTime,
                                     final ResponseHelper responseHelper) {
        return renderImageStream(renderParameters,
                                 Utils.JPEG_FORMAT,
                                 IMAGE_JPEG_MIME_TYPE,
                                 optimizeRenderTime,
                                 responseHelper);
    }


    private Response renderPngImage(final RenderParameters renderParameters,
                                    final Boolean optimizeRenderTime,
                                    final ResponseHelper responseHelper) {
        return renderImageStream(renderParameters,
                                 Utils.PNG_FORMAT,
                                 IMAGE_PNG_MIME_TYPE,
                                 optimizeRenderTime,
                                 responseHelper);
    }

    private Response renderTiffImage(final RenderParameters renderParameters,
                                     final Boolean optimizeRenderTime,
                                     final ResponseHelper responseHelper) {
        return renderImageStream(renderParameters,
                                 Utils.TIFF_FORMAT,
                                 IMAGE_TIFF_MIME_TYPE,
                                 optimizeRenderTime,
                                 responseHelper);
    }

    private Response renderImageStream(final RenderParameters renderParameters,
                                       final String format,
                                       final String mimeType,
                                       final Boolean optimizeRenderTime,
                                       final ResponseHelper responseHelper) {

        LOG.info("renderImageStream: entry, format={}, mimeType={}", format, mimeType);

        logMemoryStats();

        Response response = null;
        try {
            final boolean optimize = (optimizeRenderTime != null) && optimizeRenderTime;
            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters, optimize);
            final BufferedImageStreamingOutput out =
                    new BufferedImageStreamingOutput(targetImage,
                                                     format,
                                                     renderParameters.isConvertToGray(),
                                                     renderParameters.getQuality());
            response = responseHelper.getImageByteResponse(out, mimeType);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        logMemoryStats();

        LOG.info("renderImageStream: exit");

        return response;
    }

    private BufferedImage validateParametersAndRenderImage(final RenderParameters renderParameters,
                                                           final boolean optimizeRenderTime)
            throws IllegalArgumentException, IllegalStateException {

        LOG.info("validateParametersAndRenderImage: entry, renderParameters={}", renderParameters);

        renderParameters.initializeDerivedValues();
        renderParameters.validate();
        renderParameters.setNumberOfThreads(1); // service requests should always be single threaded

        final BufferedImage targetImage = renderParameters.openTargetImage();

        if (optimizeRenderTime && (renderParameters.numberOfTileSpecs() > 100)) {

            // if we need to optimize render time (e.g. when we're rendering a box from a database stack)
            // and there are too many tiles to dynamically render the result quickly,
            // just render the tile bounding boxes instead ...

            final BoundingBoxRenderer boundingBoxRenderer = new BoundingBoxRenderer(renderParameters, Color.GREEN);
            boundingBoxRenderer.render(targetImage);

        } else {

            // otherwise render the real thing ...

            Render.render(renderParameters,
                          targetImage,
                          SharedImageProcessorCache.getInstance());

        }

        LOG.info("validateParametersAndRenderImage: exit");

        return targetImage;
    }

    private void logMemoryStats() {
        if (LOG.isDebugEnabled()) {
            final Runtime runtime = Runtime.getRuntime();
            final double totalMb = runtime.totalMemory() / ONE_MEGABYTE;
            final double freeMb = runtime.freeMemory() / ONE_MEGABYTE;
            final double usedMb = totalMb - freeMb;
            LOG.debug("logMemoryStats: usedMb={}, freeMb={}, totalMb={}",
                      (long) usedMb, (long) freeMb, (long) totalMb);
        }
    }

    /**
     * Helper class for checking and setting HTTP cache control headers.
     */
    private static class ResponseHelper {

        final StackMetaData stackMetaData;
        Response.ResponseBuilder notModifiedBuilder;

        public ResponseHelper() {
            this.stackMetaData = null;
            this.notModifiedBuilder = null;
        }

        public ResponseHelper(final String owner,
                              final String project,
                              final String stack,
                              final Request request,
                              final RenderDataService renderDataService)
                throws ObjectNotFoundException {

            final StackId stackId = new StackId(owner, project, stack);
            this.stackMetaData = renderDataService.getStackMetaData(stackId);
            final EntityTag eTag = getStackTag();
            this.notModifiedBuilder = request.evaluatePreconditions(eTag);
            if (this.notModifiedBuilder != null) {
                this.notModifiedBuilder = setDefaultMaxAge(notModifiedBuilder);
                LOG.debug("requested unmodified resource in {}", stackId);
            }
        }

        public EntityTag getStackTag() {
            // Using eTag based upon last modified time instead of directly specifying the last modified time
            // to allow for other non-time based tags in the future.
            return new EntityTag(String.valueOf(stackMetaData.getLastModifiedTimestamp().getTime()));
        }

        public boolean isModified() {
            return (notModifiedBuilder == null);
        }

        public Response getNotModifiedResponse() {
            return notModifiedBuilder.build();
        }

        public Response getImageByteResponse(final BufferedImageStreamingOutput imageByteStream,
                                             final String mimeType) {
            Response.ResponseBuilder responseBuilder = Response.ok(imageByteStream, mimeType);
            if (stackMetaData != null) {
                final EntityTag eTag = getStackTag();
                responseBuilder = responseBuilder.tag(eTag);
                responseBuilder = setDefaultMaxAge(responseBuilder);
            }
            return responseBuilder.build();
        }

        public static Response.ResponseBuilder setDefaultMaxAge(final Response.ResponseBuilder builder) {
            final CacheControl cc = new CacheControl();
            cc.setMaxAge(3600); // 1 hour
            return builder.cacheControl(cc);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private static final String IMAGE_JPEG_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_PNG_MIME_TYPE = "image/png";
    private static final String IMAGE_TIFF_MIME_TYPE = "image/tiff";

    private static final int ONE_MEGABYTE = 1024 * 1024;

    /** Omits cache control information from responses. */
    private static final ResponseHelper NO_CACHE_HELPER = new ResponseHelper();
}
