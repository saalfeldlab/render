package org.janelia.render.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;

/**
 * APIs for validating JSON representations of the Render model objects.
 *
 * These methods are provided because cryptic error messages are usually returned
 * when deserialization errors occur in standard service usage.
 *
 * @author Eric Trautman
 */
@Path("/")
@Api(tags = {"Validation APIs"})
public class ValidationService {

    @Path("v1/owner/{owner}/validate-json/render")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateRenderParametersJson(@PathParam("owner") final String owner,
                                                 final String json) {
        LOG.info("validateRenderParametersJson: entry, owner={}", owner);
        final String context = RenderParameters.class.getName() + " instance";
        Response response;
        try {
            final RenderParameters renderParameters = RenderParameters.parseJson(json);
            renderParameters.initializeDerivedValues();
            renderParameters.validate();
            response = getParseSuccessResponse(context, String.valueOf(renderParameters));
        } catch (final Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("v1/owner/{owner}/validate-json/tile")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJson(@PathParam("owner") final String owner,
                                     final String json) {
        LOG.info("validateTileJson: entry, owner={}", owner);
        final String context = TileSpec.class.getName() + " instance";
        Response response;
        try {
            final TileSpec tileSpec = TileSpec.fromJson(json);
            tileSpec.validate();
            response = getParseSuccessResponse(context, String.valueOf(tileSpec));
        } catch (final Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("v1/owner/{owner}/validate-json/tile-array")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJsonArray(@PathParam("owner") final String owner,
                                          final String json) {
        LOG.info("validateTileJsonArray: entry, owner={}", owner);
        final String context = "array of " + TileSpec.class.getName() + " instances";
        Response response;
        try {
            final List<TileSpec> list = TileSpec.fromJsonArray(json);
            list.forEach(TileSpec::validateMipmaps);
            final String value = "array of " + list.size() + " tile specifications";
            response = getParseSuccessResponse(context, value);
        } catch (final Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("v1/owner/{owner}/validate-json/transform")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJson(@PathParam("owner") final String owner,
                                          final String json) {
        LOG.info("validateTransformJson: entry, owner={}", owner);
        final String context = TransformSpec.class.getName() + " instance";
        Response response;
        try {
            final TransformSpec transformSpec = TransformSpec.fromJson(json);
            transformSpec.validate();
            response = getParseSuccessResponse(context, String.valueOf(transformSpec));
        } catch (final Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("v1/owner/{owner}/validate-json/transform-array")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJsonArray(@PathParam("owner") final String owner,
                                               final String json) {
        LOG.info("validateTransformJsonArray: entry, owner={}", owner);
        final String context = "array of " + TransformSpec.class.getName() + " instances";
        Response response;
        try {
            final List<TransformSpec> list = TransformSpec.fromJsonArray(json);
            final Map<String, TransformSpec> map = new HashMap<>((int) (list.size() * 1.5));
            list.stream().filter(TransformSpec::hasId).forEach(spec -> map.put(spec.getId(), spec));
            for (final TransformSpec spec : list) {
                spec.resolveReferences(map);
                spec.validate();
            }
            final String value = "array of " + list.size() + " transform specifications";
            response = getParseSuccessResponse(context, value);
        } catch (final Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    private Response getParseFailureResponse(final Throwable t,
                                             final String context,
                                             final String json) {
        final String message = "Failed to parse " + context +
                               " from JSON text.  Specific error is: " + t.getMessage() + "\n";
        String logJson = json;
        final int maxMsgLength = 1024;
        if (json.length() > maxMsgLength) {
            logJson = json.substring(0, maxMsgLength) + "...";
        }
        LOG.warn(message + "  JSON text is:\n" + logJson, t);

        final Response.ResponseBuilder responseBuilder = Response.status(Response.Status.BAD_REQUEST).entity(message);
        return responseBuilder.build();
    }

    private Response getParseSuccessResponse(final String context,
                                             final String value) {
        final String message = "Successfully parsed " + context + " from JSON text.  Parsed value is: " + value + ".\n";
        LOG.info(message);
        final Response.ResponseBuilder responseBuilder = Response.ok(message);
        return responseBuilder.build();
    }

    private static final Logger LOG = LoggerFactory.getLogger(ValidationService.class);
}
