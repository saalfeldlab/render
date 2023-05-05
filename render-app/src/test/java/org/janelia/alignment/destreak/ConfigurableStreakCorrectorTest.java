package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;

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

    public static final int[][] LEAF_3R_REGIONS_TO_CLEAR = {

    		//{0, 3945, 4971, 28},
    		{0, 3945, 4503, 28},
    		{4503, 3953, 452, 15}
    };

    public static final ConfigurableMaskStreakCorrector LEAF_3R_CORRECTOR =
            new ConfigurableMaskStreakCorrector(8,
                                                5041,
                                                7920,
                                                0,
                                                0,
                                                LEAF_3R_REGIONS_TO_CLEAR);

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

    // TODO: to simplify clear region derivation, loop through correction process with different extraX|Y values and create scrollable stack from results

    @SuppressWarnings({"ConstantConditions", "deprecation"})
    public static void main(final String[] args) {

        new ImageJ();

        final ImagePlus imp =
                new ImagePlus("/nrs/stern/em_data/jrc_22ak351-leaf-3r/streaks/leaf-3r-streak-tile.tif" );
        imp.setProcessor(imp.getProcessor().convertToByteProcessor());

        final boolean displayCorrectionData = true;
        if (displayCorrectionData) {

            // original code from Preibisch that displays correction data
            final Img<UnsignedByteType> img = ImageJFunctions.wrapByte(imp );

            ImageJFunctions.show( img ).setTitle( "input" );
            final double avg = StreakCorrector.avgIntensity(img);
            System.out.println( avg );

            // show FFT and bandpass images to manually find clear region rectangles for filtering
            // (choose Macro > Record to see the numbers)
            final boolean showFFTAndBandpass = true;

            // remove streaking (but it'll introduce a wave pattern)
            final Img<UnsignedByteType> imgCorr =
                    LEAF_3R_CORRECTOR.fftBandpassCorrection(img,
                                                            showFFTAndBandpass);

            // create the wave pattern introduced by the filtering above
            final Img<FloatType> patternCorr = LEAF_3R_CORRECTOR.createPattern(imgCorr.dimensionsAsLongArray(), avg);

            // removes the wave pattern from the corrected image
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
            LEAF_3R_CORRECTOR.process(imp.getProcessor(), 1.0);
            imp.show();

        }

        SimpleMultiThreading.threadHaltUnClean();
    }
}
