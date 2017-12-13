package org.janelia.alignment.betterbox;

import java.awt.image.BufferedImage;
import java.io.File;

import org.janelia.alignment.Utils;

/**
 * Cached pixels for a box that has been rendered to disk.
 *
 * @author Eric Trautman
 */
public class RenderedBox {

    private final File file;
    private final BufferedImage image;

    /**
     * Loads image pixels from the specified file.
     *
     * @param  file  rendered image file.
     */
    public RenderedBox(final File file) {
        this(file, Utils.openImage(file.getAbsolutePath()));
    }

    /**
     * Tracks specified image file and pixel data.
     *
     * @param  file   image file.
     * @param  image  image pixels.
     */
    public RenderedBox(final File file,
                       final BufferedImage image) {
        this.file = file;
        this.image = image;
    }

    /**
     * @return file for this rendered box.
     */
    public File getFile() {
        return file;
    }

    /**
     * @return pixels for this rendered box.
     */
    public BufferedImage getImage() {
        return image;
    }
}
