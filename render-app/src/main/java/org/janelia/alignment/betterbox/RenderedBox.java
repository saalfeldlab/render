package org.janelia.alignment.betterbox;

import java.awt.image.BufferedImage;
import java.io.File;

import org.janelia.alignment.Utils;

/**
 * Cached pixels for a box that has been rendered to disk.
 *
 * @author Eric Trautman
 */
public record RenderedBox(File file, BufferedImage image) {

    /**
     * Loads image pixels from the specified file.
     *
     * @param file rendered image file.
     */
    public RenderedBox(final File file) {
        this(file, Utils.openImage(file.getAbsolutePath()));
    }

    /**
     * Tracks specified image file and pixel data.
     *
     * @param file  image file.
     * @param image image pixels.
     */
    public RenderedBox {}
}
