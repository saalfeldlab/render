package org.janelia.render.client.response;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.janelia.alignment.json.JsonUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Translates JSON response content stream into an object of the specified class.
 *
 * @author Eric Trautman
 */
public class JsonResponseHandler<T>
        extends BaseResponseHandler
        implements ResponseHandler<T> {

    private final Class<T> classOfT;

    /**
     * @param  requestContext  context (e.g. "GET http://janelia.org") for use in error messages.
     * @param  classOfT        response object class.
     */
    public JsonResponseHandler(String requestContext,
                               Class<T> classOfT) {
        super(requestContext);
        this.classOfT = classOfT;
    }

    @Override
    public T handleResponse(HttpResponse response)
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
        return JsonUtils.GSON.fromJson(reader, classOfT);
    }
}
