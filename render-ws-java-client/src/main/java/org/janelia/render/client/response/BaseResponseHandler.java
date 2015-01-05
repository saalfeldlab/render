package org.janelia.render.client.response;

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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Base class containing common response handling methods.
 *
 * @author Eric Trautman
 */
public class BaseResponseHandler {

    public static final String TEXT_PLAIN_MIME_TYPE = ContentType.TEXT_PLAIN.getMimeType();
    public static final String JSON_MIME_TYPE = ContentType.APPLICATION_JSON.getMimeType();

    public static final Set<Integer> OK = new HashSet<Integer>(Arrays.asList(HttpStatus.SC_OK));
    public static final Set<Integer> CREATED = new HashSet<Integer>(Arrays.asList(HttpStatus.SC_CREATED));

    private String requestContext;

    /**
     * @param  requestContext  context (e.g. "PUT http://janelia.org") for use in error messages.
     */
    public BaseResponseHandler(String requestContext) {
        this.requestContext = requestContext;
    }

    public String getRequestContext() {
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
    public String getResponseBodyText(HttpEntity entity)
            throws IOException {

        String text = null;

        final Header contentTypeHeader = entity.getContentType();
        if (contentTypeHeader != null) {
            if (TEXT_PLAIN_MIME_TYPE.equals(contentTypeHeader.getValue())) {
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
    public HttpEntity getValidatedResponseEntity(HttpResponse response,
                                                 Set<Integer> validStatusCodes)
            throws IOException {

        final StatusLine statusLine = response.getStatusLine();
        final int statusCode = statusLine.getStatusCode();
        final HttpEntity entity = response.getEntity();

        if (! validStatusCodes.contains(statusCode)) {
            String responseBodyText = null;
            try {
                responseBodyText = getResponseBodyText(entity);
            } catch (Throwable t) {
                LOG.warn("failed to parse entity content for error response, ignoring parse failure", t);
            }
            throw new ClientProtocolException("HTTP status " + statusCode + " with body\n\n  " + responseBodyText +
                                              "\n\nreturned for\n\n  " + requestContext + "\n");
        }

        return entity;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BaseResponseHandler.class);
}
