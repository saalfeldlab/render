package org.janelia.render.client.spark.cache;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.annotation.Nonnull;

import org.apache.commons.io.FileUtils;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a canvas to disk and loads its metadata (e.g. path and size) into the cache.
 *
 * @author Eric Trautman
 */
public class CanvasFileLoader
        extends CanvasDataLoader {

    private final String canvasFormat;
    private final boolean fillWithNoise;
    private final File rootDirectory;

    /**
     *
     * @param  renderParametersUrlTemplate  template for deriving render parameters URL for each canvas.
     * @param  canvasFormat                 image format for all rendered canvas files.
     * @param  parentDirectory              parent directory for all rendered canvas files.
     */
    public CanvasFileLoader(final String renderParametersUrlTemplate,
                            final boolean fillWithNoise,
                            final String canvasFormat,
                            final File parentDirectory) {
        super(renderParametersUrlTemplate, CachedCanvasFile.class);
        this.fillWithNoise = fillWithNoise;
        this.canvasFormat = canvasFormat;
        this.rootDirectory = new File(parentDirectory, FILE_CACHE_DIRECTORY_NAME);
    }

    @Override
    public CachedCanvasFile load(@Nonnull final CanvasId canvasId) throws Exception {

        File groupDirectory = rootDirectory;
        if (canvasId.getGroupId() != null) {
            groupDirectory = new File(rootDirectory, canvasId.getGroupId());
        }

        final File renderFile = new File(groupDirectory, canvasId.getId() + "." + canvasFormat);

        final RenderParameters renderParameters = getRenderParameters(canvasId);

        final BufferedImage bufferedImage = ArgbRenderer.renderWithNoise(renderParameters, fillWithNoise);

        Utils.saveImage(bufferedImage,
                        renderFile,
                        renderParameters.isConvertToGray(),
                        renderParameters.getQuality());

        return new CachedCanvasFile(renderFile, renderParameters);
    }

    public void deleteRootDirectory()
            throws IOException {
        deleteRootDirectory(rootDirectory);
    }

    private static synchronized void deleteRootDirectory(final File rootDirectory)
            throws IOException {

        if (! FILE_CACHE_DIRECTORY_NAME.equals(rootDirectory.getName())) {
            throw new IOException("invalid name for file cache root directory " + rootDirectory +
                                  ", skipping recursive delete");
        }

        if (rootDirectory.exists()) {
            FileUtils.deleteDirectory(rootDirectory);
            LOG.info("deleted {}", rootDirectory);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasFileLoader.class);

    private static final String FILE_CACHE_DIRECTORY_NAME = "canvas_file_cache";
}
