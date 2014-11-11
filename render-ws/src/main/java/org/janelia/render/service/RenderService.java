package org.janelia.render.service;

import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.TileSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.awt.image.BufferedImage;
import java.net.UnknownHostException;

/**
 * APIs that use the {@link Render} tool to render images.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
public class RenderService {

    private RenderDataService renderDataService;

    @SuppressWarnings("UnusedDeclaration")
    public RenderService()
            throws UnknownHostException {
        this(new RenderDataService());
    }

    public RenderService(RenderDataService renderDataService) {
        this.renderDataService = renderDataService;
    }

    @Path("jpeg-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImage(RenderParameters renderParameters) {
        LOG.info("renderJpegImage: entry, renderParameters={}", renderParameters);
        return renderImageStream(renderParameters, Utils.JPEG_FORMAT, IMAGE_JPEG_MIME_TYPE);
    }


    @Path("png-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderPngImage(RenderParameters renderParameters) {
        LOG.info("renderPngImage: entry, renderParameters={}", renderParameters);
        return renderImageStream(renderParameters, Utils.PNG_FORMAT, IMAGE_PNG_MIME_TYPE);
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/scale/{scale}/jpeg-image")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response renderJpegImageForTile(@PathParam("owner") String owner,
                                           @PathParam("project") String project,
                                           @PathParam("stack") String stack,
                                           @PathParam("tileId") String tileId,
                                           @PathParam("scale") Double scale) {

        LOG.info("renderJpegImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}",
                 owner, project, stack, tileId, scale);

        RenderParameters renderParameters = null;
        try {
            final TileSpec tileSpec = renderDataService.getTileSpec(owner, project, stack, tileId, true);
            renderParameters = new RenderParameters(null,
                                                    tileSpec.getMinX(),
                                                    tileSpec.getMinY(),
                                                    tileSpec.getWidth(),
                                                    tileSpec.getHeight(),
                                                    scale);
            renderParameters.addTileSpec(tileSpec);
        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return renderJpegImage(renderParameters);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImageForBox(@PathParam("owner") String owner,
                                          @PathParam("project") String project,
                                          @PathParam("stack") String stack,
                                          @PathParam("x") Double x,
                                          @PathParam("y") Double y,
                                          @PathParam("z") Double z,
                                          @PathParam("width") Integer width,
                                          @PathParam("height") Integer height,
                                          @PathParam("scale") Double scale) {

        LOG.info("renderJpegImageForBox: entry");
        final StackId stackId = new StackId(owner, project, stack);
        final RenderParameters renderParameters = renderDataService.getInternalRenderParameters(stackId,
                                                                                                x,
                                                                                                y,
                                                                                                z,
                                                                                                width,
                                                                                                height,
                                                                                                scale);
        return renderJpegImage(renderParameters);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    public Response renderPngImageForBox(@PathParam("owner") String owner,
                                         @PathParam("project") String project,
                                         @PathParam("stack") String stack,
                                         @PathParam("x") Double x,
                                         @PathParam("y") Double y,
                                         @PathParam("z") Double z,
                                         @PathParam("width") Integer width,
                                         @PathParam("height") Integer height,
                                         @PathParam("scale") Double scale) {

        LOG.info("renderPngImageForBox: entry");
        final StackId stackId = new StackId(owner, project, stack);
        final RenderParameters renderParameters = renderDataService.getInternalRenderParameters(stackId,
                                                                                                x,
                                                                                                y,
                                                                                                z,
                                                                                                width,
                                                                                                height,
                                                                                                scale);
        return renderPngImage(renderParameters);
    }

    private Response renderImageStream(RenderParameters renderParameters,
                                       String format,
                                       String mimeType) {

        LOG.info("renderImageStream: entry, format={}, mimeType={}", format, mimeType);

        logMemoryStats();

        Response response = null;
        try {
            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters);
            final BufferedImageStreamingOutput out =
                    new BufferedImageStreamingOutput(targetImage, format, renderParameters.getQuality());

            final Response.ResponseBuilder responseBuilder = Response.ok(out, mimeType);
            response = responseBuilder.build();

        } catch (Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        logMemoryStats();

        LOG.info("renderImageStream: exit");

        return response;
    }

    private BufferedImage validateParametersAndRenderImage(RenderParameters renderParameters)
            throws IllegalArgumentException, IllegalStateException {

        LOG.info("validateParametersAndRenderImage: entry, renderParameters={}", renderParameters);

        renderParameters.initializeDerivedValues();
        renderParameters.validate();

        final BufferedImage targetImage = renderParameters.openTargetImage();

        Render.render(renderParameters.getTileSpecs(),
                      targetImage,
                      renderParameters.getX(),
                      renderParameters.getY(),
                      renderParameters.getRes(),
                      renderParameters.getScale(),
                      renderParameters.isAreaOffset(),
                      renderParameters.skipInterpolation());

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

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private static final String IMAGE_JPEG_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_PNG_MIME_TYPE = "image/png";

    private static final int ONE_MEGABYTE = 1024 * 1024;
}
