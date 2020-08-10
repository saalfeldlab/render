package org.janelia.alignment.loader;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

/**
 * Uses ImageJ to load individual slices from a 3D TIFF stack.
 * Note that ImageJ assumes that slices are indexed 1 .. N.
 *
 * @author Eric Trautman
 */
public class ImageJTiffStackLoader
        extends SliceLoader {

    public ImageJTiffStackLoader(final int sliceNumber) {
        super(sliceNumber);
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

        final ImagePlus imagePlus;
        try {
            imagePlus = opener.openImage(path, getSliceNumber());
        } catch (final Throwable t) {
            throw new IllegalArgumentException(getErrorMessage(urlString), t);
        }

        if (imagePlus == null) {
            throw new IllegalArgumentException(getErrorMessage(urlString));
        }

        return imagePlus.getProcessor();
    }

    private String getErrorMessage(final String urlString) {
        return "failed to create imagePlus instance for slice " + getSliceNumber() + " of '" + urlString + "'";
    }
}
