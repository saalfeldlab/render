package org.janelia.render.client.response;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.janelia.alignment.json.JsonUtils;

/**
 * Translates JSON response content stream into an object of the specified class.
 *
 * @author Eric Trautman
 */
public class JsonResponseHandler<T>
        extends BaseResponseHandler
        implements ResponseHandler<T> {

    private final JsonUtils.Helper<T> helper;
    private final JsonUtils.GenericHelper<T> genericHelper;

    /**
     * Constructs a handler suitable for deserialization of non-generic class instances.
     *
     * @param  requestContext  context (e.g. "GET http://janelia.org") for use in error messages.
     */
    public JsonResponseHandler(final String requestContext,
                               final JsonUtils.Helper<T> helper) {
        super(requestContext);
        this.helper = helper;
        this.genericHelper = null;
    }

    /**
     * Constructs a handler suitable for deserialization of generic class instances.
     *
     * @param  requestContext  context (e.g. "GET http://janelia.org") for use in error messages.
     */
    public JsonResponseHandler(final String requestContext,
                               final JsonUtils.GenericHelper<T> genericHelper) {
        super(requestContext);
        this.helper = null;
        this.genericHelper = genericHelper;
    }

    @Override
    public T handleResponse(final HttpResponse response)
            throws IOException {

        final HttpEntity entity = getValidatedResponseEntity(response, OK);
        final Header contentTypeHeader = entity.getContentType();

        if (contentTypeHeader == null) {
            throw new ClientProtocolException("content type header missing for\n\n  " + getRequestContext() + "\n");
        } else if (! JSON_MIME_TYPE.equals(contentTypeHeader.getValue())) {
            throw new ClientProtocolException("invalid mime type '" + contentTypeHeader.getValue() + "' for\n\n  " +
                                              getRequestContext() + "\n\n(expected '" + JSON_MIME_TYPE + "')");
        }

        final Reader reader = new InputStreamReader(entity.getContent());
        if (helper != null) {
            return helper.fromJson(reader);
        } else {
            return genericHelper.fromJson(reader);
        }
    }
}
