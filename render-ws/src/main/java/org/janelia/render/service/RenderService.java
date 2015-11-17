package org.janelia.render.service;

import java.net.UnknownHostException;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.util.RenderServiceUtil;
import org.janelia.render.service.util.ResponseHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * APIs that use the {@link Render} tool to render images.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
@Api(tags = {"Image APIs"})
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
    @Produces(RenderServiceUtil.IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(
            tags = "Spec Image APIs",
            value = "Render JPEG image from a provide spec")
    public Response renderJpegImageFromProvidedParameters(final RenderParameters renderParameters) {
        return RenderServiceUtil.renderImageStream(renderParameters,
                                                   Utils.JPEG_FORMAT,
                                                   RenderServiceUtil.IMAGE_JPEG_MIME_TYPE,
                                                   false,
                                                   ResponseHelper.NO_CACHE_HELPER);
    }


    @Path("png-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(RenderServiceUtil.IMAGE_PNG_MIME_TYPE)
    @ApiOperation(
            tags = "Spec Image APIs",
            value = "Render PNG image from a provide spec")
    public Response renderPngImageFromProvidedParameters(final RenderParameters renderParameters) {
        return RenderServiceUtil.renderImageStream(renderParameters,
                                                   Utils.PNG_FORMAT,
                                                   RenderServiceUtil.IMAGE_PNG_MIME_TYPE,
                                                   false,
                                                   ResponseHelper.NO_CACHE_HELPER);
    }

    @Path("tiff-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(RenderServiceUtil.IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(
            tags = "Spec Image APIs",
            value = "Render TIFF image from a provide spec")
    public Response renderTiffImageFromProvidedParameters(final RenderParameters renderParameters) {
        return RenderServiceUtil.renderImageStream(renderParameters,
                                                   Utils.TIFF_FORMAT,
                                                   RenderServiceUtil.IMAGE_TIFF_MIME_TYPE,
                                                   false,
                                                   ResponseHelper.NO_CACHE_HELPER);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/jpeg-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(
            tags = "Section Image APIs",
            value = "Render JPEG image for a section")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParametersForZ(owner, project, stack, z, scale, filter);
            return RenderServiceUtil.renderJpegImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/png-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_PNG_MIME_TYPE)
    @ApiOperation(
            tags = "Section Image APIs",
            value = "Render PNG image for a section")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParametersForZ(owner, project, stack, z, scale, filter);
            return RenderServiceUtil.renderPngImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/tiff-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(
            tags = "Section Image APIs",
            value = "Render TIFF image for a section")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    renderDataService.getRenderParametersForZ(owner, project, stack, z, scale, filter);
            return RenderServiceUtil.renderTiffImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(
            tags = "Bounding Box Image APIs",
            value = "Render JPEG image for the specified bounding box")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, null,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return RenderServiceUtil.renderJpegImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_PNG_MIME_TYPE)
    @ApiOperation(
            tags = "Bounding Box Image APIs",
            value = "Render PNG image for the specified bounding box")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, null,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return RenderServiceUtil.renderPngImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/tiff-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(
            tags = "Bounding Box Image APIs",
            value = "Render TIFF image for the specified bounding box")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, null,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return RenderServiceUtil.renderTiffImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_JPEG_MIME_TYPE)
    @ApiOperation(
            tags = "Bounding Box Image APIs",
            value = "Render JPEG image for the specified bounding box and groupId")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, groupId,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return RenderServiceUtil.renderJpegImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_PNG_MIME_TYPE)
    @ApiOperation(
            tags = "Bounding Box Image APIs",
            value = "Render PNG image for the specified bounding box and groupId")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, groupId,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return RenderServiceUtil.renderPngImage(renderParameters, optimizeRenderTime, responseHelper);
        } else {
            return responseHelper.getNotModifiedResponse();
        }
    }

    @Path("project/{project}/stack/{stack}/group/{groupId}/z/{z}/box/{x},{y},{width},{height},{scale}/tiff-image")
    @GET
    @Produces(RenderServiceUtil.IMAGE_TIFF_MIME_TYPE)
    @ApiOperation(
            tags = "Bounding Box Image APIs",
            value = "Render TIFF image for the specified bounding box and groupId")
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

        final ResponseHelper responseHelper = new ResponseHelper(request, getStackMetaData(owner, project, stack));
        if (responseHelper.isModified()) {
            final RenderParameters renderParameters =
                    getRenderParametersForGroupBox(owner, project, stack, groupId,
                                                   x, y, z, width, height, scale, filter, binaryMask);
            return RenderServiceUtil.renderTiffImage(renderParameters, optimizeRenderTime, responseHelper);
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

    private StackMetaData getStackMetaData(final String owner,
                                           final String project,
                                           final String stack) {
        final StackId stackId = new StackId(owner, project, stack);
        return renderDataService.getStackMetaData(stackId);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);
}
