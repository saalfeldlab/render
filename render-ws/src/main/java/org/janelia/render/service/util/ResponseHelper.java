package org.janelia.render.service.util;

import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for checking and setting HTTP cache control headers.
 *
 * @author Eric Trautman
 */
public class ResponseHelper {

    /** Omits cache control information from responses. */
    public static final ResponseHelper NO_CACHE_HELPER = new ResponseHelper();

    private final StackMetaData stackMetaData;
    private Response.ResponseBuilder notModifiedBuilder;

    public ResponseHelper() {
        this.stackMetaData = null;
        this.notModifiedBuilder = null;
    }

    public ResponseHelper(final Request request,
                          final StackMetaData stackMetaData)
            throws ObjectNotFoundException {

        this.stackMetaData = stackMetaData;
        final EntityTag eTag = getStackTag();
        this.notModifiedBuilder = request.evaluatePreconditions(eTag);
        if (this.notModifiedBuilder != null) {
            this.notModifiedBuilder = setDefaultMaxAge(notModifiedBuilder);
            LOG.debug("requested unmodified resource in {}", stackMetaData.getStackId());
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

    public Response getImageByteResponse(final StreamingOutput imageByteStream,
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

    private static final Logger LOG = LoggerFactory.getLogger(ResponseHelper.class);

}


