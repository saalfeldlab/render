package org.janelia.render.client.response;

import java.io.IOException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;

/**
 * Handles response content stream (if any exists) as plain text.
 *
 * @author Eric Trautman
 */
public class TextResponseHandler
        extends BaseResponseHandler
        implements ResponseHandler<String> {

    /**
     * Constructs a handler suitable for deserialization of text response messages.
     *
     * @param  requestContext  context (e.g. "GET <a href="http://janelia.org">...</a>") for use in error messages.
     */
    public TextResponseHandler(final String requestContext) {
        super(requestContext);
    }

    @Override
    public String handleResponse(final HttpResponse response)
            throws IOException {
        final HttpEntity entity = getValidatedResponseEntity(response, OK);
        return getResponseBodyText(entity);
    }
}
