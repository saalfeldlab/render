package org.janelia.render.client.response;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class containing common response handling methods.
 *
 * @author Eric Trautman
 */
class BaseResponseHandler {

    private static final String TEXT_PLAIN_MIME_TYPE = ContentType.TEXT_PLAIN.getMimeType();
    static final String JSON_MIME_TYPE = ContentType.APPLICATION_JSON.getMimeType();

    static final Set<Integer> OK = new HashSet<>(Collections.singletonList(HttpStatus.SC_OK));
    static final Set<Integer> CREATED = new HashSet<>(Collections.singletonList(HttpStatus.SC_CREATED));

    private final String requestContext;

    /**
     * @param  requestContext  context (e.g. "PUT http://janelia.org") for use in error messages.
     */
    BaseResponseHandler(final String requestContext) {
        this.requestContext = requestContext;
    }

    String getRequestContext() {
        return requestContext;
    }

    /**
     * @param  entity  response entity.
     *
     * @return the entity content as a string if has "text/plain" mime type, otherwise null.
     *
     * @throws IOException
     *   if the entity content cannot be read.
     */
    String getResponseBodyText(final HttpEntity entity)
            throws IOException {

        String text = null;

        final Header contentTypeHeader = entity.getContentType();
        if (contentTypeHeader != null) {
            final String contentTypeValue = contentTypeHeader.getValue();
            if ((contentTypeValue != null) && contentTypeValue.startsWith(TEXT_PLAIN_MIME_TYPE)) {
                text = IOUtils.toString(entity.getContent());
            }
        }

        return text;
    }

    /**
     * Validates the response status code.
     *
     * @param  response  HTTP response to check.
     *
     * @return response entity if it is valid.
     *
     * @throws IOException
     *   if an invalid
     */
    HttpEntity getValidatedResponseEntity(final HttpResponse response,
                                          final Set<Integer> validStatusCodes)
            throws IOException {

        final StatusLine statusLine = response.getStatusLine();
        final int statusCode = statusLine.getStatusCode();
        final HttpEntity entity = response.getEntity();

        if (! validStatusCodes.contains(statusCode)) {
            String responseBodyText = null;
            try {
                responseBodyText = getResponseBodyText(entity);
            } catch (final Throwable t) {
                LOG.warn("failed to parse entity content for error response, ignoring parse failure", t);
            }
            throw new ClientProtocolException("HTTP status " + statusCode + " with body\n\n  " + responseBodyText +
                                              "\n\nreturned for\n\n  " + requestContext + "\n");
        }

        return entity;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BaseResponseHandler.class);
}
