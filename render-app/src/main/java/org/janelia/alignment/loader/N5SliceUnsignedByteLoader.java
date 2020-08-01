package org.janelia.alignment.loader;

import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.UnsignedByteType;

/**
 * Loads a 2D slice of an n5 volume that has 8-bit pixels.
 *
 * @author Eric Trautman
 */
public class N5SliceUnsignedByteLoader
        extends N5SliceLoader<UnsignedByteType, ByteProcessor> {

    /** Shareable instance of this loader. */
    public static final N5SliceUnsignedByteLoader INSTANCE = new N5SliceUnsignedByteLoader();

    @Override
    public ByteProcessor buildImageProcessor(final int width,
                                             final int height) {
        return new ByteProcessor(width, height);
    }

    @Override
    public RandomAccessibleInterval<UnsignedByteType> setupTarget(final ByteProcessor forImageProcessor) {
        return ArrayImgs.unsignedBytes(
                (byte[]) forImageProcessor.getPixels(),
                forImageProcessor.getWidth(),
                forImageProcessor.getHeight());
    }
}
