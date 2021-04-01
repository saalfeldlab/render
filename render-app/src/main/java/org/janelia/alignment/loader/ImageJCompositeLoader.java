package org.janelia.alignment.loader;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;

/**
 * Uses logic from {@link ij.plugin.CompositeConverter} to load n-channel image and
 * merge the channels into a composite single channel RGB image.
 *
 * @author Eric Trautman
 */
public class ImageJCompositeLoader
        implements ImageLoader {

    /** Shareable instance of this loader. */
    public static final ImageJCompositeLoader INSTANCE = new ImageJCompositeLoader();

    /**
     * @return true (always) because the default ImageJ loader handles 2D sources.
     */
    @Override
    public boolean hasSame3DContext(final ImageLoader otherLoader) {
        return true;
    }

    @Override
    public ImageProcessor load(final String urlString)
            throws IllegalArgumentException {

        // openers keep state about the file being opened, so we need to create a new opener for each load
        final Opener opener = new Opener();
        opener.setSilentMode(true);

        String path = urlString;
        if (urlString.startsWith("file:")) { // note: not worrying about file://<host>/<path> URLs for now
            path = urlString.substring(5);
        }

        ImagePlus imagePlus;
        try {
            imagePlus = opener.openImage(path);
        } catch (final Throwable t) {
            throw new IllegalArgumentException(getErrorMessage(urlString), t);
        }

        if (! (imagePlus instanceof CompositeImage)) {
            throw new IllegalArgumentException(getErrorMessage(urlString));
        }

        // convert multi-channel source into single channel composite ...
        ((CompositeImage) imagePlus).setMode(IJ.COMPOSITE);
        imagePlus.updateImage();
        final BufferedImage bi = imagePlus.getBufferedImage();
        imagePlus = new ImagePlus("composite", bi);

        return imagePlus.getProcessor();
    }

    private String getErrorMessage(final String urlString) {
        return "failed to create CompositeImage instance for '" + urlString + "'";
    }

}