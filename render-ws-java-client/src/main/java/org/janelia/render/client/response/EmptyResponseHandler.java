package org.janelia.render.client.response;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;

/**
 * Handles empty responses.
 *
 * @author Eric Trautman
 */
public class EmptyResponseHandler
        extends BaseResponseHandler
        implements ResponseHandler<Void> {

    private final Set<Integer> validStatusCodes;

    /**
     * Constructs a handler that expects responses with an {@link org.apache.http.HttpStatus#SC_OK} status code.
     *
     * @param  requestContext  context (e.g. "GET http://janelia.org") for use in error messages.
     */
    public EmptyResponseHandler(final String requestContext) {
        this(requestContext, OK);
    }

    /**
     * Constructs a handler that expects responses with one of the specified status codes.
     *
     * @param  requestContext    context (e.g. "GET http://janelia.org") for use in error messages.
     * @param  validStatusCodes  set of valid response status codes.
     */
    public EmptyResponseHandler(final String requestContext,
                                final Set<Integer> validStatusCodes) {
        super(requestContext);
        this.validStatusCodes = new HashSet<>(validStatusCodes);
    }


    @Override
    public Void handleResponse(final HttpResponse response)
            throws IOException {
        getValidatedResponseEntity(response, validStatusCodes);
        return null;
    }
}
