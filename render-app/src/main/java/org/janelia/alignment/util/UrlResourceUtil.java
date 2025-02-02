package org.janelia.alignment.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Shared URL management utilities.
 *
 * @author Eric Trautman
 */
public class UrlResourceUtil {

    public static final UrlResourceUtil DEFAULT_INSTANCE = new UrlResourceUtil();

    private final int bufferSize;

    public UrlResourceUtil() {
        this(DEFAULT_BUFFER_SIZE);
    }

    public UrlResourceUtil(final int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public Reader getExtensionBasedReader(final URL url)
            throws IOException {

        final InputStream inputStream;

        final URLConnection connection = url.openConnection();

        final String path = url.getPath();
        if (path.endsWith(".gz")) {
            inputStream = new GZIPInputStream(connection.getInputStream());
        } else if (path.endsWith(".zip")) {
            inputStream = new ZipInputStream(connection.getInputStream());
        } else {
            inputStream = new BufferedInputStream(connection.getInputStream(), bufferSize);
        }

        return new InputStreamReader(inputStream);
    }

    private static final int DEFAULT_BUFFER_SIZE = 65536;

}
