package org.janelia.alignment.mapper;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRandomAccessible;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.RandomAccessibleOnRealRandomAccessible;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 * Maps source pixels from an unmasked single channel source to a target canvas.
 */
public class SingleChannelMapper
        implements PixelMapper {

    protected final ImageProcessorWithMasks normalizedSource;
    protected final ImageProcessorWithMasks target;
    protected final boolean isMappingInterpolated;

	final Img<UnsignedByteType> img;
	final RealRandomAccessible<UnsignedByteType> rra;
	final RealRandomAccess<UnsignedByteType> access;
	final AffineTransform2D t, tInv;
	final double[] tmp;

	final int subsampling = 3;

    public SingleChannelMapper(final ImageProcessorWithMasks source,
                               final ImageProcessorWithMasks target,
                               final boolean isMappingInterpolated) {

        this.normalizedSource = normalizeSourceForTarget(source, target.ip);
        this.target = target;
        this.isMappingInterpolated = isMappingInterpolated;

        if (isMappingInterpolated)
        {
            this.normalizedSource.ip.setInterpolationMethod(ImageProcessor.BILINEAR);
        	this.img = ImageJFunctions.wrapByte( new ImagePlus( "", normalizedSource.ip ) );
        	//this.rra = Views.interpolate( Views.extendZero( img ), new NLinearInterpolatorFactory<>() );
        	//this.access = rra.realRandomAccess();
        	this.tmp = new double[ 2 ];

        	this.rra = createSubsampled( img, subsampling );
        	this.access = rra.realRandomAccess();

        	// transform to undo the subsampling
        	this.t = new AffineTransform2D();
        	this.t.scale( subsampling );
        	this.tInv = t.inverse();
        }
        else
        {
        	throw new RuntimeException( "not supported for subsampling" );
        }
    }

    // TOOD: remove synchronized (just there so it is shown once for testing)
    public static RealRandomAccessible<UnsignedByteType> createSubsampled( final Img<UnsignedByteType> img, final int subsampling )
    {
    	// right now always takes the first pixel
    	// to do advanced types of downsampling we need to implement our own version of net.imglib2.view.SubsampleIntervalView / net.imglib2.view.SubsampleView
    	// to use e.g. the center pixel for 3x3, we simply need to translate the underlying img
    	final RandomAccessibleInterval<UnsignedByteType> sub = Views.subsample( img, subsampling );

    	// bordering is necessary so the interpolation on the smaller image does not create black borders
    	final RealRandomAccessible<UnsignedByteType> rraSub = Views.interpolate( Views.extendBorder( sub ), new NLinearInterpolatorFactory<>() );

    	/*
    	final AffineTransform2D t = new AffineTransform2D();
    	t.scale( subsampling );
    	final RealRandomAccessible<UnsignedByteType> rraSubScaled = RealViews.affine( rraSub, t );
    	final RandomAccessible<UnsignedByteType> rastered = Views.raster( rraSubScaled );
    	final RandomAccessibleInterval<UnsignedByteType> rasteredInterval = Views.interval( rastered, img );
    	
    	ImageJFunctions.show( img ).setTitle( "original" );
    	ImageJFunctions.show( sub ).setTitle( "subsampled");
    	ImageJFunctions.show( rasteredInterval ).setTitle( "restored");

    	SimpleMultiThreading.threadHaltUnClean(); */
    	return rraSub;
    }

    @Override
    public int getTargetWidth() {
        return target.getWidth();
    }

    @Override
    public int getTargetHeight() {
        return target.getHeight();
    }

    @Override
    public boolean isMappingInterpolated() {
        return isMappingInterpolated;
    }

    @Override
    public void map(final double sourceX,
                    final double sourceY,
                    final int targetX,
                    final int targetY) {

        final int roundedSourceX = (int) (sourceX + 0.5f);
        final int roundedSourceY = (int) (sourceY + 0.5f);
        target.ip.set(targetX, targetY, normalizedSource.ip.get(roundedSourceX, roundedSourceY));
    }

    @Override
    public void mapInterpolated(final double sourceX,
                                final double sourceY,
                                final int targetX,
                                final int targetY) {

    	// old code:
        //target.ip.set(targetX, targetY, normalizedSource.ip.getPixelInterpolated(sourceX, sourceY));

    	tmp[ 0 ] = sourceX;
    	tmp[ 1 ] = sourceY;

    	// we interpolate on the subsampled image
    	tInv.apply( tmp, tmp );
    	access.setPosition( tmp );

        target.ip.set(targetX, targetY, access.get().get() );

    	//ImageJFunctions.show( img );
    	//SimpleMultiThreading.threadHaltUnClean();


        //target: ij.process.ByteProcessor
        //normalizedSource: ij.process.ByteProcessor
        //System.out.println( "target: " + target.ip.getClass().getName() );
        //System.out.println( "normalizedSource: " + normalizedSource.ip.getClass().getName() );
        // TODO: subsampling needs to go here
    }

    public static ImageProcessorWithMasks normalizeSourceForTarget(final ImageProcessorWithMasks source,
                                                                   final ImageProcessor target)
            throws IllegalArgumentException {

        final ImageProcessorWithMasks normalizedSource;

        if (target.getClass().equals(source.ip.getClass())) {
            normalizedSource = source; // no need to normalize
        } else if (target instanceof ByteProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToByteProcessor(),
                                                source.mask,
                                                null);
        } else if (target instanceof ShortProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToShortProcessor(),
                                                source.mask,
                                                null);
        } else if (target instanceof FloatProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToFloatProcessor(),
                                                source.mask,
                                                null);
        } else if (target instanceof ColorProcessor) {
            normalizedSource =
                    new ImageProcessorWithMasks(source.ip.convertToColorProcessor(),
                                                source.mask,
                                                null);
        } else {
            throw new IllegalArgumentException("conversion to " + target.getClass() + " is not supported");
        }

        return normalizedSource;
    }

}
