package org.janelia.render.client.response;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Converts response content stream to a {@link BufferedImage}.
 *
 * @author Eric Trautman
 */
public class BufferedImageResponseHandler
        extends BaseResponseHandler
        implements ResponseHandler<BufferedImage> {

    /**
     * @param  requestContext  context (e.g. "PUT http://janelia.org") for use in error messages.
     */
    public BufferedImageResponseHandler(String requestContext) {
        super(requestContext);
    }

    @Override
    public BufferedImage handleResponse(HttpResponse response)
            throws IOException {

        final HttpEntity entity = getValidatedResponseEntity(response, OK);
        final InputStream in = entity.getContent();

        return ImageIO.read(in);
    }
}
