package org.janelia.render.client.zspacing.loader;

import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 * Interface for loading aligned layer pixels for z position correction.
 *
 * @author Eric Trautman
 */
public interface LayerLoader {

    /**
     * Pair of image and (optional) mask processors that can be used by z spacing code.
     */
    class FloatProcessors {

        public FloatProcessor image;
        public FloatProcessor mask;

        public FloatProcessors(final ImageProcessor image,
                               final ImageProcessor mask) {
            this.image = image instanceof FloatProcessor ? (FloatProcessor) image : image.convertToFloatProcessor();
            if (mask == null) {
                this.mask = null;
            } else {
                this.mask = mask instanceof FloatProcessor ? (FloatProcessor) mask : mask.convertToFloatProcessor();
            }
        }
    }

    /**
     * @return total number of slices to load.
     */
    int getNumberOfLayers();

    /**
     * @return image and (optional) mask processors for the specified layer.
     */
    FloatProcessors getProcessors(final int layerIndex);

}
