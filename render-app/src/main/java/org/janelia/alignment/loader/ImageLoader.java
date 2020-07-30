package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Describes methods required for all image loaders and
 * provides convenience {@link #build} method to construct loader instances.
 *
 * @author Eric Trautman
 */
public interface ImageLoader {

    enum LoaderType {
        IMAGEJ_DEFAULT, IMAGEJ_TIFF_STACK
    }

    boolean hasSame3DContext(@Nonnull final ImageLoader otherLoader);

    ImageProcessor load(@Nonnull final String urlString) throws IllegalArgumentException;

    /**
     * @return loader instance based upon specified parameters.
     *
     * @throws IllegalArgumentException
     *   if a loader instance cannot be constructed for any reason.
     */
    static ImageLoader build(@Nullable final LoaderType loaderType,
                             @Nullable final Integer sliceNumber)
            throws IllegalArgumentException {

        ImageLoader imageLoader = ImageJDefaultLoader.DEFAULT_LOADER;

        if (loaderType != null) {
            switch (loaderType) {
                case IMAGEJ_DEFAULT:
                    break;
                case IMAGEJ_TIFF_STACK:
                    if (sliceNumber == null) {
                        throw new IllegalArgumentException(
                                "slice number must be defined for " + loaderType + " loaders");
                    }
                    imageLoader = new ImageJTiffStackLoader(sliceNumber);
                    break;
            }
        }

        return imageLoader;
    }

}
