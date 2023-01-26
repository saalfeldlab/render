package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;

import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;


/**
 * Mask implementation that corrects streaks in the Z07422_17_VNC_1 volume.
 *
 * @author Stephan Preibisch
 */
public class StreakCorrectorZ042217VNC1 extends StreakCorrector {

    public StreakCorrectorZ042217VNC1(final int numThreads) {
        super(numThreads);
    }

    public Img<FloatType> createMask(final Dimensions dim) {
        if ( dim.dimension( 0 ) != 6161 || dim.dimension( 1 ) != 10920 )
            throw new RuntimeException( "this mask is hard-coded for an FFT size of 6161x10920.");

        final ArrayImg<FloatType, FloatArray> mask = ArrayImgs.floats(dim.dimensionsAsLongArray() );

        for ( final FloatType t : mask )
            t.setOne();

        final int extraY = 6;

        //makeRectangle(6043, 5459, 113, 3);
        clear(mask, 6043, 5459, 113, 3, extraY);

        //makeRectangle(5960, 5458, 83, 5);
        clear(mask, 5960, 5458, 83, 5, extraY);

        //makeRectangle(5798, 5456, 162, 9);
        clear(mask, 5798, 5456, 162, 9, extraY);

        //makeRectangle(5573, 5453, 225, 15);
        clear(mask, 5573, 5453, 225, 15, extraY);

        //makeRectangle(0, 5448, 5573, 25);
        clear(mask, 0, 5448, 5573, 25, extraY);

        // top stripe
        //makeRectangle(0, 5360, 4569, 5);
        clear(mask, 0, 5360, 4569, 5, extraY);

        // bot stripe
        //makeRectangle(0, 5556, 4569, 5);
        clear(mask, 0, 5556, 4569, 5, extraY);

        // does not work, image gets darker
        //Gauss3.gauss(1, Views.extendPeriodic( mask ), mask );

        return mask;
    }

    @SuppressWarnings("deprecation")
    public static void main(final String[] args) {

        new ImageJ();

        final StreakCorrectorZ042217VNC1 streakCorrector = new StreakCorrectorZ042217VNC1(8);
        final ImagePlus imp = new ImagePlus("/Users/trautmane/Desktop/stern/streak_fix/rendered_images/22-08-23_114401_0-0-2.28132.0.tif" );
        final Img<UnsignedByteType> img = ImageJFunctions.wrapByte(imp );

        //ImageJFunctions.show( img ).setTitle( "input" );
        final double avg = StreakCorrector.avgIntensity(img);
        System.out.println( avg );

        final Img<UnsignedByteType> imgCorr = streakCorrector.fftBandpassCorrection( img );
        final Img<FloatType> patternCorr = streakCorrector.createPattern(imgCorr.dimensionsAsLongArray(), avg);
        final RandomAccessibleInterval<UnsignedByteType> fixed =
                Converters.convertRAI(imgCorr,
                                      patternCorr,
                                      (i1,i2,o) ->
                                              o.set(Math.max(0,
                                                             Math.min( 255, Math.round( i1.get() - i2.get() ) ) ) ),
                                      new UnsignedByteType());

        ImageJFunctions.show( imgCorr ).setTitle( "imgCorr" );
        ImageJFunctions.show( patternCorr ).setTitle( "patternCorr" );
        ImageJFunctions.show( fixed ).setTitle( "fixed" );

        SimpleMultiThreading.threadHaltUnClean();
    }
}
