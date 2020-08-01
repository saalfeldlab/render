package org.janelia.alignment.loader;

import ij.process.FloatProcessor;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Loads a 2D slice of an n5 volume that has 32-bit floating-point pixels.
 *
 * @author Eric Trautman
 */
public class N5SliceFloatLoader
        extends N5SliceLoader<FloatType, FloatProcessor> {

    /** Shareable instance of this loader. */
    public static final N5SliceFloatLoader INSTANCE = new N5SliceFloatLoader();

    @Override
    public FloatProcessor buildImageProcessor(final int width,
                                             final int height) {
        return new FloatProcessor(width, height);
    }

    @Override
    public RandomAccessibleInterval<FloatType> setupTarget(final FloatProcessor forImageProcessor) {
        return ArrayImgs.floats(
                (float[]) forImageProcessor.getPixels(),
                forImageProcessor.getWidth(),
                forImageProcessor.getHeight());
    }
}
