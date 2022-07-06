package org.janelia.alignment.loader;

import ij.process.ImageProcessor;

/**
 * Describes methods required for all image loaders and
 * provides convenience {@link #build} method to construct loader instances.
 *
 * @author Eric Trautman
 */
public interface ImageLoader {

    enum LoaderType {
        IMAGEJ_DEFAULT, IMAGEJ_TIFF_STACK, H5_SLICE, N5_SLICE, IMAGEJ_COMPOSITE, DYNAMIC_MASK
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

                case H5_SLICE:
                    imageLoader = HDF5SliceLoader.INSTANCE;
                    break;

                case N5_SLICE:
                    imageLoader = N5SliceLoader.INSTANCE;
                    break;

                case IMAGEJ_COMPOSITE:
                    imageLoader = ImageJCompositeLoader.INSTANCE;
                    break;

                case DYNAMIC_MASK:
                    imageLoader = DynamicMaskLoader.INSTANCE;
                    break;
            }
        }

        return imageLoader;
    }

}
