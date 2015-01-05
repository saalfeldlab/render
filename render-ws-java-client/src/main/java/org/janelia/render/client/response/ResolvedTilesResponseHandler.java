package org.janelia.render.client.response;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Translates response content stream to a {@link ResolvedTileSpecCollection} object.
 *
 * @author Eric Trautman
 */
public class ResolvedTilesResponseHandler
        extends BaseResponseHandler
        implements ResponseHandler<ResolvedTileSpecCollection> {

    /**
     * @param  requestContext  context (e.g. "GET http://janelia.org") for use in error messages.
     */
    public ResolvedTilesResponseHandler(String requestContext) {
        super(requestContext);
    }

    @Override
    public ResolvedTileSpecCollection handleResponse(HttpResponse response)
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
        return JsonUtils.GSON.fromJson(reader, ResolvedTileSpecCollection.class);
    }
}
