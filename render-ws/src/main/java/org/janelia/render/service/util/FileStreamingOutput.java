package org.janelia.render.service.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;

/**
 * Wrapper for {@link File} instances that need to be
 * streamed as the response for a JAX-RS API request.
 *
 * @author Eric Trautman
 */
public class FileStreamingOutput
        implements StreamingOutput {

    private final File file;

    public FileStreamingOutput(final File file) {
        this.file = file;
    }

    @Override
    public void write(final OutputStream outputStream)
            throws IOException, WebApplicationException {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            IOUtils.copyLarge(inputStream, outputStream);
        }
    }

}
