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
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.janelia.alignment.BoundingBoxRenderer;
import org.janelia.alignment.Render;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.service.util.BufferedImageStreamingOutput;
import org.janelia.render.service.util.RenderServiceUtil;
import org.janelia.render.service.util.SharedImageProcessorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * APIs that use the {@link Render} tool to render images.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}")
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
    public Response renderJpegImageFromProvidedParameters(final RenderParameters renderParameters) {
        return renderImageStream(renderParameters, Utils.JPEG_FORMAT, IMAGE_JPEG_MIME_TYPE, false);
    }


    @Path("png-image")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderPngImageFromProvidedParameters(final RenderParameters renderParameters) {
        return renderImageStream(renderParameters, Utils.PNG_FORMAT, IMAGE_PNG_MIME_TYPE, false);
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/scale/{scale}/jpeg-image")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response renderJpegImageForTile(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack,
                                           @PathParam("tileId") final String tileId,
                                           @PathParam("scale") final Double scale,
                                           @QueryParam("filter") final Boolean filter) {

        LOG.info("renderJpegImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final RenderParameters renderParameters =
                renderDataService.getRenderParameters(owner, project, stack, tileId, scale, filter);
        return renderJpegImage(renderParameters, false);
    }

    @Path("project/{project}/stack/{stack}/tile/{tileId}/scale/{scale}/png-image")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response renderPngImageForTile(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("tileId") final String tileId,
                                          @PathParam("scale") final Double scale,
                                          @QueryParam("filter") final Boolean filter) {

        LOG.info("renderPngImageForTile: entry, owner={}, project={}, stack={}, tileId={}, scale={}, filter={}",
                 owner, project, stack, tileId, scale, filter);

        final RenderParameters renderParameters =
                renderDataService.getRenderParameters(owner, project, stack, tileId, scale, filter);
        return renderPngImage(renderParameters, false);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/jpeg-image")
    @GET
    @Produces(IMAGE_JPEG_MIME_TYPE)
    public Response renderJpegImageForBox(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("x") final Double x,
                                          @PathParam("y") final Double y,
                                          @PathParam("z") final Double z,
                                          @PathParam("width") final Integer width,
                                          @PathParam("height") final Integer height,
                                          @PathParam("scale") final Double scale,
                                          @QueryParam("filter") final Boolean filter) {

        LOG.info("renderJpegImageForBox: entry");
        final StackId stackId = new StackId(owner, project, stack);
        final RenderParameters renderParameters = renderDataService.getInternalRenderParameters(stackId,
                                                                                                x,
                                                                                                y,
                                                                                                z,
                                                                                                width,
                                                                                                height,
                                                                                                scale);
        renderParameters.setDoFilter(filter);

        return renderJpegImage(renderParameters, true);
    }

    @Path("project/{project}/stack/{stack}/z/{z}/box/{x},{y},{width},{height},{scale}/png-image")
    @GET
    @Produces(IMAGE_PNG_MIME_TYPE)
    public Response renderPngImageForBox(@PathParam("owner") final String owner,
                                         @PathParam("project") final String project,
                                         @PathParam("stack") final String stack,
                                         @PathParam("x") final Double x,
                                         @PathParam("y") final Double y,
                                         @PathParam("z") final Double z,
                                         @PathParam("width") final Integer width,
                                         @PathParam("height") final Integer height,
                                         @PathParam("scale") final Double scale,
                                         @QueryParam("filter") final Boolean filter) {

        LOG.info("renderPngImageForBox: entry");
        final StackId stackId = new StackId(owner, project, stack);
        final RenderParameters renderParameters = renderDataService.getInternalRenderParameters(stackId,
                                                                                                x,
                                                                                                y,
                                                                                                z,
                                                                                                width,
                                                                                                height,
                                                                                                scale);
        renderParameters.setDoFilter(filter);

        return renderPngImage(renderParameters, true);
    }

    private Response renderJpegImage(final RenderParameters renderParameters,
                                     final boolean optimizeRenderTime) {
        return renderImageStream(renderParameters, Utils.JPEG_FORMAT, IMAGE_JPEG_MIME_TYPE, optimizeRenderTime);
    }


    private Response renderPngImage(final RenderParameters renderParameters,
                                    final boolean optimizeRenderTime) {
        return renderImageStream(renderParameters, Utils.PNG_FORMAT, IMAGE_PNG_MIME_TYPE, optimizeRenderTime);
    }

    private Response renderImageStream(final RenderParameters renderParameters,
                                       final String format,
                                       final String mimeType,
                                       final boolean optimizeRenderTime) {

        LOG.info("renderImageStream: entry, format={}, mimeType={}", format, mimeType);

        logMemoryStats();

        Response response = null;
        try {
            final BufferedImage targetImage = validateParametersAndRenderImage(renderParameters, optimizeRenderTime);
            final BufferedImageStreamingOutput out =
                    new BufferedImageStreamingOutput(targetImage,
                                                     format,
                                                     renderParameters.isConvertToGray(),
                                                     renderParameters.getQuality());

            final Response.ResponseBuilder responseBuilder = Response.ok(out, mimeType);
            response = responseBuilder.build();

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

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private static final String IMAGE_JPEG_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_PNG_MIME_TYPE = "image/png";

    private static final int ONE_MEGABYTE = 1024 * 1024;
}
