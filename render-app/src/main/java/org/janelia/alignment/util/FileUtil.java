package org.janelia.alignment.util;

import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Shared file management utilities.
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
        saveJsonFile(path, data, JsonUtils.MAPPER);
    }

    public static void saveJsonFile(final String path,
                                    final Object data,
                                    final ObjectMapper mapper)
            throws IOException {

        final Path toPath = Paths.get(path).toAbsolutePath();

        try (final Writer writer = DEFAULT_INSTANCE.getExtensionBasedWriter(toPath.toString())) {
            mapper.writeValue(writer, data);
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

    public static boolean deleteRecursive(final File file) {

        // could use Apache Commons FileUtils.delete but to have the logging, need to implement here

        boolean deleteSuccessful = true;

        if (file.isDirectory()){
            final File[] files = file.listFiles();
            if (files != null) {
                for (final File f : files) {
                    deleteSuccessful = deleteSuccessful && deleteRecursive(f);
                }
            }
        }

        if (file.delete()) {
            LOG.info("deleted " + file.getAbsolutePath());
        } else {
            LOG.warn("failed to delete " + file.getAbsolutePath());
            deleteSuccessful = false;
        }

        return deleteSuccessful;
    }

    public static File createBatchedZDirectory(final String rootOutputDirectory,
                                               final String batchDirectoryPrefix,
                                               final Double z) {

        final String batchDirectoryFormat = batchDirectoryPrefix + "%03d";
        final int zBatch = (int) (z / 100);
        final Path imageDirPath = Paths.get(rootOutputDirectory,
                                            String.format(batchDirectoryFormat, zBatch)).toAbsolutePath();
        final File imageDir = imageDirPath.toFile();

        ensureWritableDirectory(imageDir);

        return imageDir;
    }

    private static final Logger LOG = LoggerFactory.getLogger(FileUtil.class);

    private static final int DEFAULT_BUFFER_SIZE = 65536;

}
