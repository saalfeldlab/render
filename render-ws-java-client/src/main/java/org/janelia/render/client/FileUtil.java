package org.janelia.render.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.janelia.alignment.json.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public FileUtil(final int bufferSize) {
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

    public static void saveJsonFile(final String path,
                                    final Object data)
            throws IOException {

        final Path toPath = Paths.get(path).toAbsolutePath();

        LOG.info("saveJsonFile: entry");

        try (final Writer writer = DEFAULT_INSTANCE.getExtensionBasedWriter(toPath.toString())) {
            JsonUtils.MAPPER.writeValue(writer, data);
        } catch (final Throwable t) {
            throw new IOException("failed to write " + toPath, t);
        }

        LOG.info("saveJsonFile: exit, wrote data to {}", toPath);
    }

    public static void ensureWritableDirectory(final File directory) {
        // try twice to work around concurrent access issues
        if (! directory.exists()) {
            if (! directory.mkdirs()) {
                if (! directory.exists()) {
                    // last try
                    if (! directory.mkdirs()) {
                        if (! directory.exists()) {
                            throw new IllegalArgumentException("failed to create " + directory);
                        }
                    }
                }
            }
        }
        if (! directory.canWrite()) {
            throw new IllegalArgumentException("not allowed to write to " + directory);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(FileUtil.class);

    private static final int DEFAULT_BUFFER_SIZE = 65536;
}
