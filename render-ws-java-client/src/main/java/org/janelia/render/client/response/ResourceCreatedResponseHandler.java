package org.janelia.render.client.response;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;

import java.io.IOException;

/**
 * Handler for responses where an {@link org.apache.http.HttpStatus#SC_CREATED} status code is expected.
 * If available, the location header value is returned.
 *
 * @author Eric Trautman
 */
public class ResourceCreatedResponseHandler
        extends BaseResponseHandler
        implements ResponseHandler<String> {

    /**
     * @param  requestContext  context (e.g. "PUT http://janelia.org") for use in error messages.
     */
    public ResourceCreatedResponseHandler(String requestContext) {
        super(requestContext);
    }

    @Override
    public String handleResponse(HttpResponse response)
            throws IOException {

        getValidatedResponseEntity(response, CREATED);

        final Header locationHeader = response.getFirstHeader("Location");

        String locationValue = null;
        if (locationHeader != null) {
            locationValue = locationHeader.getValue();
        }

        return locationValue;
    }
}
