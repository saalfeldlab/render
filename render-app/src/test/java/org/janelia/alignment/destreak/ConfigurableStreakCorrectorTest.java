package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.Map;

import org.janelia.alignment.filter.FilterSpec;
import org.junit.Assert;
import org.junit.Test;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Tests the {@link ConfigurableMaskStreakCorrector} class.
 * Main method includes original visualization of Z0422_17_VNC_1_CORRECTOR written by @StephanPreibisch
 *
 * @author Eric Trautman
 */
public class ConfigurableStreakCorrectorTest {

    public static final int[][] Z0422_17_VNC_1_REGIONS_TO_CLEAR = {
            {6043, 5459, 113, 3},
            {5960, 5458, 83, 5},
            {5798, 5456, 162, 9},
            {5573, 5453, 225, 15},
            {0, 5448, 5573, 25},
            {0, 5360, 4569, 5},
            {0, 5556, 4569, 5}
    };

    public static final ConfigurableMaskStreakCorrector Z0422_17_VNC_1_CORRECTOR =
            new ConfigurableMaskStreakCorrector(8,
                                                6161,
                                                10920,
                                                0,
                                                6,
                                                Z0422_17_VNC_1_REGIONS_TO_CLEAR);

    @Test
    public void testParameterSerializationAndParsing() {

        final Map<String, String> parametersMap = Z0422_17_VNC_1_CORRECTOR.toParametersMap();

        final ConfigurableMaskStreakCorrector deserializedCorrector = new ConfigurableMaskStreakCorrector();
        deserializedCorrector.init(parametersMap);

        final String expectedDataString = Z0422_17_VNC_1_CORRECTOR.toDataString();
        final String actualDataString = deserializedCorrector.toDataString();
        Assert.assertEquals("data strings should match", expectedDataString, actualDataString);

        final FilterSpec filterSpec = new FilterSpec(ConfigurableMaskStreakCorrector.class.getName(),
                                                     parametersMap);


        System.out.println("Z0422_17_VNC_1_CORRECTOR filter spec JSON:");
        System.out.println(filterSpec.toJson());
    }

    @SuppressWarnings({"ConstantConditions", "deprecation"})
    public static void main(final String[] args) {

        new ImageJ();

        final ImagePlus imp =
                new ImagePlus("/Users/trautmane/Desktop/stern/streak_fix/rendered_images/22-08-23_114401_0-0-2.28132.0.tif" );

        final boolean displayCorrectionData = true;
        if (displayCorrectionData) {

            // original code from Preibisch that displays correction data
            final Img<UnsignedByteType> img = ImageJFunctions.wrapByte(imp );

            //ImageJFunctions.show( img ).setTitle( "input" );
            final double avg = StreakCorrector.avgIntensity(img);
            System.out.println( avg );

            final Img<UnsignedByteType> imgCorr = Z0422_17_VNC_1_CORRECTOR.fftBandpassCorrection( img );
            final Img<FloatType> patternCorr = Z0422_17_VNC_1_CORRECTOR.createPattern(imgCorr.dimensionsAsLongArray(), avg);
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

        } else {

            // simply display fixed result (using same logic copied into process method without correction display)
            final ImageProcessor fixedProcessor = Z0422_17_VNC_1_CORRECTOR.process(imp.getProcessor(), 1.0);
            new ImagePlus("fixedProcessor", fixedProcessor).show();

        }

        SimpleMultiThreading.threadHaltUnClean();
    }
}
