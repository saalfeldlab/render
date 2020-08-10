package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

/**
 * Describes methods required for all image loaders and
 * provides convenience {@link #build} method to construct loader instances.
 *
 * @author Eric Trautman
 */
public interface ImageLoader {

    // TODO: add N5_SLICE_SHORT, N5_SLICE_COLOR, ... once generic n5 load process is stabilized

    enum LoaderType {
        IMAGEJ_DEFAULT, IMAGEJ_TIFF_STACK, N5_SLICE_UNSIGNED_BYTE, N5_SLICE_FLOAT
    }

    boolean hasSame3DContext(final ImageLoader otherLoader);

    ImageProcessor load(final String urlString) throws IllegalArgumentException;

    /**
     * @return loader instance based upon specified parameters.
     *
     * @throws IllegalArgumentException
     *   if a loader instance cannot be constructed for any reason.
     */
    static ImageLoader build(final LoaderType loaderType,
                             final Integer sliceNumber)
            throws IllegalArgumentException {

        ImageLoader imageLoader = ImageJDefaultLoader.INSTANCE;

        if (loaderType != null) {
            switch (loaderType) {

                case IMAGEJ_DEFAULT:
                    break;

                case IMAGEJ_TIFF_STACK:

                    // TODO: consider refactoring sliceNumber into URL for TIFF stacks if n5 URL approach is accepted
                    if (sliceNumber == null) {
                        throw new IllegalArgumentException(
                                "slice number must be defined for " + loaderType + " loaders");
                    }
                    imageLoader = new ImageJTiffStackLoader(sliceNumber);
                    break;

                case N5_SLICE_UNSIGNED_BYTE:
                    imageLoader = N5SliceUnsignedByteLoader.INSTANCE;
                    break;

                case N5_SLICE_FLOAT:
                    imageLoader = N5SliceFloatLoader.INSTANCE;
                    break;
            }
        }

        return imageLoader;
    }

}
