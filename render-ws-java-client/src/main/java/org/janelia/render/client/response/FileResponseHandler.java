package org.janelia.render.client.response;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Writes response content stream to a file.
 *
 * @author Eric Trautman
 */
public class FileResponseHandler
        extends BaseResponseHandler
        implements ResponseHandler<File> {

    private File file;

    /**
     * @param  requestContext  context (e.g. "PUT http://janelia.org") for use in error messages.
     * @param  file            file to which response should be written.
     */
    public FileResponseHandler(String requestContext,
                               File file) {
        super(requestContext);
        this.file = file;
    }

    @Override
    public File handleResponse(HttpResponse response)
            throws IOException {

        final HttpEntity entity = getValidatedResponseEntity(response, OK);

        final InputStream in = entity.getContent();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            IOUtils.copyLarge(in, out);
        } finally {
            IOUtils.closeQuietly(out);
        }

        return file;
    }
}
