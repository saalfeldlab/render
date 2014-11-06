package org.janelia.render.service;

import com.google.gson.reflect.TypeToken;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * APIs for validating JSON representations of the Render model objects.
 *
 * These methods are provided because cryptic error messages are usually returned
 * when deserialization errors occur in standard service usage.
 *
 * @author Eric Trautman
 */
@Path("/v1/owner/{owner}/validate-json")
public class ValidationService {

    @Path("render")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateRenderParametersJson(@PathParam("owner") String owner,
                                                 String json) {
        LOG.info("validateRenderParametersJson: entry, owner={}", owner);
        final String context = RenderParameters.class.getName() + " instance";
        Response response;
        try {
            final RenderParameters renderParameters = RenderParameters.parseJson(json);
            renderParameters.initializeDerivedValues();
            renderParameters.validate();
            response = getParseSuccessResponse(context, String.valueOf(renderParameters));
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("tile")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJson(@PathParam("owner") String owner,
                                     String json) {
        LOG.info("validateTileJson: entry, owner={}", owner);
        final String context = TileSpec.class.getName() + " instance";
        Response response;
        try {
            final TileSpec tileSpec = TileSpec.fromJson(json);
            tileSpec.validate();
            response = getParseSuccessResponse(context, String.valueOf(tileSpec));
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("tile-array")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTileJsonArray(@PathParam("owner") String owner,
                                          String json) {
        LOG.info("validateTileJsonArray: entry, owner={}", owner);
        final String context = "array of " + TileSpec.class.getName() + " instances";
        Response response;
        try {
            final Type listType = new TypeToken<ArrayList<TileSpec>>(){}.getType();
            final List<TileSpec> list = JsonUtils.GSON.fromJson(json, listType);
            for (TileSpec spec : list) {
                spec.validateMipmaps();
            }
            final String value = "array of " + list.size() + " tile specifications";
            response = getParseSuccessResponse(context, value);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("transform")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJson(@PathParam("owner") String owner,
                                          String json) {
        LOG.info("validateTransformJson: entry, owner={}", owner);
        final String context = TransformSpec.class.getName() + " instance";
        Response response;
        try {
            final TransformSpec transformSpec = JsonUtils.GSON.fromJson(json, TransformSpec.class);
            transformSpec.validate();
            response = getParseSuccessResponse(context, String.valueOf(transformSpec));
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    @Path("transform-array")
    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.TEXT_PLAIN)
    public Response validateTransformJsonArray(@PathParam("owner") String owner,
                                               String json) {
        LOG.info("validateTransformJsonArray: entry, owner={}", owner);
        final String context = "array of " + TransformSpec.class.getName() + " instances";
        Response response;
        try {
            final Type listType = new TypeToken<ArrayList<TransformSpec>>(){}.getType();
            final List<TransformSpec> list = JsonUtils.GSON.fromJson(json, listType);
            final Map<String, TransformSpec> map = new HashMap<String, TransformSpec>((int) (list.size() * 1.5));
            for (TransformSpec spec : list) {
                if (spec.hasId()) {
                    map.put(spec.getId(), spec);
                }
            }
            for (TransformSpec spec : list) {
                spec.resolveReferences(map);
                spec.validate();
            }
            final String value = "array of " + list.size() + " transform specifications";
            response = getParseSuccessResponse(context, value);
        } catch (Throwable t) {
            response = getParseFailureResponse(t, context, json);
        }
        return response;
    }

    private Response getParseFailureResponse(Throwable t,
                                             String context,
                                             String json) {
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

    private Response getParseSuccessResponse(String context,
                                             String value) {
        final String message = "Successfully parsed " + context + " from JSON text.  Parsed value is: " + value + ".\n";
        LOG.info(message);
        final Response.ResponseBuilder responseBuilder = Response.ok(message);
        return responseBuilder.build();
    }

    private static final Logger LOG = LoggerFactory.getLogger(ValidationService.class);
}
