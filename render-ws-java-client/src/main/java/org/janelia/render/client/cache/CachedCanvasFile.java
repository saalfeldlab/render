package org.janelia.render.client.cache;

import java.io.File;

import org.janelia.alignment.RenderParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache container for a canvas' rendered image.
 *
 * @author Eric Trautman
 */
public class CachedCanvasFile implements CachedCanvasData {

    private final File renderedImage;
    private final RenderParameters renderParameters;

    CachedCanvasFile(final File renderedImage,
                     final RenderParameters renderParameters) {
        this.renderedImage = renderedImage;
        this.renderParameters = renderParameters;
    }

    File getRenderedImage() {
        return renderedImage;
    }

    public RenderParameters getRenderParameters() {
        return renderParameters;
    }

    public long getKilobytes() {
        long kilobytes = 0;
        if (renderedImage.exists()) {
            final long len = renderedImage.length();
            kilobytes = len / ONE_KILOBYTE;
            if ((len % ONE_KILOBYTE) > 0) {
                kilobytes++; // round up to nearest kb
            }
        }
        return kilobytes;
    }

    @Override
    public String toString() {
        return String.valueOf(renderedImage);
    }

    /**
     * Removes this file from the local file system.
     * Any exceptions that occur during removal are simply logged (and ignored).
     */
    public void remove() {
        removeFile(renderedImage);
    }

    private void removeFile(final File file) {
        if ((file != null) && file.isFile()) {
            try {
                if (file.delete()) {
                    LOG.debug("removeFile: removed {}", file.getAbsolutePath());
                } else {
                    LOG.warn("removeFile: failed to remove {}", file.getAbsolutePath());
                }
            } catch (final Throwable t) {
                LOG.warn("removeFile: failed to remove " + file.getAbsolutePath(), t);
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CachedCanvasFile.class);

    private static final long ONE_KILOBYTE = 1024;
}