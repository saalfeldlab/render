package org.janelia.alignment.match.cache;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.util.ImageProcessorCache;
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
    private final File rootDirectory;

    /**
     * @param  urlTemplate                  template for deriving render parameters URL for each canvas.
     * @param  canvasFormat                 image format for all rendered canvas files.
     * @param  parentDirectory              parent directory for all rendered canvas files.
     */
    public CanvasFileLoader(final String canvasFormat,
                            final File parentDirectory) {
        super(CachedCanvasFile.class);
        this.canvasFormat = canvasFormat;
        this.rootDirectory = new File(parentDirectory, FILE_CACHE_DIRECTORY_NAME);
    }

    @Override
    public CachedCanvasFile load(final CanvasIdWithRenderContext canvasIdWithRenderContext) throws Exception {

        final CanvasId canvasId = canvasIdWithRenderContext.getCanvasId();

        File groupDirectory = rootDirectory;
        if (canvasId.getGroupId() != null) {
            groupDirectory = new File(rootDirectory, canvasId.getGroupId());
        }

        final File renderFile = new File(groupDirectory, canvasId.getId() + "." + canvasFormat);

        final RenderParameters renderParameters = canvasIdWithRenderContext.loadRenderParameters();

        final BufferedImage bufferedImage = renderParameters.openTargetImage();

        ArgbRenderer.render(renderParameters, bufferedImage, ImageProcessorCache.DISABLED_CACHE);

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
