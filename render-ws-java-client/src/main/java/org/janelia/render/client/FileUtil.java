package org.janelia.render.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Utility for reading and writing files.
 *
 * @author Eric Trautman
 */
public class FileUtil {

    public static final FileUtil DEFAULT_INSTANCE = new FileUtil();

    private final int bufferSize;

    public FileUtil() {
        this(DEFAULT_BUFFER_SIZE);
    }

    public FileUtil(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public Reader getExtensionBasedReader(final String fullPathName)
            throws IOException {

        final InputStream inputStream;

        if (fullPathName.endsWith(".gz")) {
            inputStream = new GZIPInputStream(new FileInputStream(fullPathName));
        } else if (fullPathName.endsWith(".zip")) {
            inputStream = new ZipInputStream(new FileInputStream(fullPathName));
        } else {
            inputStream = new BufferedInputStream(new FileInputStream(fullPathName), bufferSize);
        }

        return new InputStreamReader(inputStream);
    }

    public Writer getExtensionBasedWriter(final String fullPathName)
            throws IOException {

        final OutputStream outputStream;

        if (fullPathName.endsWith(".gz")) {
            outputStream = new GZIPOutputStream(new FileOutputStream(fullPathName));
        } else if (fullPathName.endsWith(".zip")) {
            outputStream = new ZipOutputStream(new FileOutputStream(fullPathName));
        } else {
            outputStream = new BufferedOutputStream(new FileOutputStream(fullPathName), bufferSize);
        }

        return new OutputStreamWriter(outputStream);
    }

    private static final int DEFAULT_BUFFER_SIZE = 65536;
}
