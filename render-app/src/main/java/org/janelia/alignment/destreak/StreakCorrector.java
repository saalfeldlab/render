package org.janelia.alignment.destreak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.fft.FourierTransform;
import net.imglib2.algorithm.fft.InverseFourierTransform;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * Reusable components for streak correction.
 * Implementations must define the {@link #createMask} method.
 *
 * @author Stephan Preibisch
 */
@SuppressWarnings("CommentedOutCode")
public abstract class StreakCorrector {

    private final int numThreads;

    public StreakCorrector(final int numThreads) {
        this.numThreads = numThreads;
    }

    public abstract Img<FloatType> createMask(final Dimensions dim );

    public static boolean showFFTImg = false;
 
    @SuppressWarnings("deprecation")
    public <T extends RealType<T> & NativeType<T>> Img< T > fftBandpassCorrection(final RandomAccessibleInterval<T> input)
    {
        final FourierTransform<T, ComplexFloatType>
                fft = new FourierTransform<>(input,
                                             new ArrayImgFactory<>(new ComplexFloatType()),
                                             new ComplexFloatType());
        fft.process();
        fft.setNumThreads( numThreads );
        final Img<ComplexFloatType> fftImg = fft.getResult();

        LOG.info("fftBandpassCorrection: {}", Util.printInterval(fftImg ) );

        // TODO: uncomment the next two lines to set the rectangle filter manually (choose Macro > Record to see the numbers)
        if ( showFFTImg )
        	ImageJFunctions.show( fftImg ).setTitle( "fft" );

        //SimpleMultiThreading.threadHaltUnClean();

        applyMask( fftImg, createMask( fftImg ) );

        if ( showFFTImg )
        	ImageJFunctions.show( fftImg ).setTitle( "fft bandpass" );

        final InverseFourierTransform< T, ComplexFloatType > ifft = new InverseFourierTransform<>(fftImg, fft );
        ifft.process();
        ifft.setNumThreads( numThreads );

        @SuppressWarnings("UnnecessaryLocalVariable")
        final Img< T > templateInverse = ifft.getResult();

        //ImageJFunctions.show( templateInverse ).setTitle("ifft");

        return templateInverse;

        //Gauss3.gauss( 500, Views.extendMirrorDouble( templateInverse ), templateInverse );
        //ImageJFunctions.show( templateInverse ).setTitle("ifft bg");

        // TODO: get pattern along X and remove

        //check: FFTConvolution<RealType<R>>

		/*
		final T type = Util.getTypeFromInterval( input );
		final RandomAccessibleInterval<T> img = Views.zeroMin( input );
		final RandomAccessibleInterval<T> out = new ArrayImgFactory<T>( type ).create( img.dimensionsAsLongArray() );
		final Img<ComplexFloatType> fft = FFT.realToComplex(img, new ArrayImgFactory<>( new ComplexFloatType() ) );
		ImageJFunctions.show( fft );
		FFT.complexToReal( fft, out );
		ImageJFunctions.show( out );
		*/
    }

    public <T extends RealType<T>> void clear(final RandomAccessibleInterval<T> img,
                                              final int x,
                                              final int y,
                                              final int w,
                                              final int h,
                                              final int extraX,
                                              final int extraY)
    {
        final RandomAccess< T > r = img.randomAccess();

        final int startY = y - extraY;
        final int stopY = y + h + extraY;
        final int startX = x - extraX;
        final int stopX = x + w + extraX;

        for (int y1 = startY; y1 < stopY; ++y1) {
            for (int x1 = startX; x1 < stopX; ++x1) {
                r.setPosition(x1, 0);
                r.setPosition(y1, 1);
                r.get().setZero();
            }
        }
    }

    public void applyMask(final RandomAccessibleInterval<ComplexFloatType> fft,
                          final RandomAccessibleInterval<FloatType> mask )
    {
        final Cursor<ComplexFloatType> cFFT = Views.flatIterable(fft ).cursor();
        final Cursor<FloatType> cM = Views.flatIterable( mask ).cursor();

        while (cM.hasNext() )
        {
            cFFT.next().mul( cM.next().get() );
        }
    }

    public Img<FloatType> createPattern(final long[] dimensions,
                                        final double avgIntensity)
    {
        final Img<FloatType> pattern = ArrayImgs.floats( dimensions );

        for ( final FloatType t : pattern )
            t.set( 1 );

        final Img<FloatType> patternFiltered = fftBandpassCorrection(pattern); // this could be loaded from disc, always the same

        for ( final FloatType t : patternFiltered )
            t.set( (float)(t.get() * avgIntensity - avgIntensity) );

        return patternFiltered;
    }

    public static < T extends RealType<T>> double avgIntensity( final RandomAccessibleInterval< T > img )
    {
        final long numPx = Views.iterable(img).size();
        final RealSum s = new RealSum((int)numPx );

        for ( final T type : Views.iterable(img) )
            s.add( type.getRealDouble() );

        return s.getSum() / (double)numPx;
    }

    private static final Logger LOG = LoggerFactory.getLogger(StreakCorrector.class);
}
