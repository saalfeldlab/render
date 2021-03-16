package org.janelia.render.client.zspacing;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import net.imglib2.multithreading.SimpleMultiThreading;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.janelia.alignment.Utils;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.thickness.inference.Options;
import org.janelia.thickness.inference.InferFromMatrix.RegularizationType;
import org.junit.Test;

/**
 * Tests the {@link HeadlessZPositionCorrection} class.
 *
 * @author Eric Trautman
 */
public class HeadlessZPositionCorrectionTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new HeadlessZPositionCorrection.MainParameters());
    }

    public static void main(final String[] args) {
        
        testEstimations();

        SimpleMultiThreading.threadHaltUnClean();

        System.exit(0); // HACK: exit here to skip "normal" test

        final String runDirectory =
                "/Users/trautmane/Desktop/zcorr/Z0720_07m_VNC/Sec32/v1_acquire_trimmed_sp1/run_20210223_015048";
        final String imagePaths = runDirectory + "/debug-images";
        final String outputFile = runDirectory + "/Zcoords.from-files.txt";

        final String[] effectiveArgs = {
//                "--imagePaths", "/Users/trautmane/Desktop/zcorr/matt",
//                "--imagePaths", "/Users/trautmane/Desktop/zcorr/crop/0125/002",
//                "--imagePaths", "/Users/trautmane/Desktop/zcorr/crop/05/002",
//                "--imagePaths", "/Users/trautmane/Desktop/zcorr/matt_Sec08",
//                "--optionsJson", "/Users/trautmane/Desktop/zcorr/test-options.json",
                "--imagePaths", imagePaths,
                "--outputFile", outputFile,
                "--zOffset", "12201"
        };

        HeadlessZPositionCorrection.main(effectiveArgs);
    }

    private static void testEstimations() {

        final String runDir = new File("").getAbsolutePath();
        System.out.println("Running testEstimations in " + runDir);

        // just using single TEM tile already in repo for other tests
        final String imageDir = runDir + "/src/test/resources/point-match-test";
        final String testPath = imageDir + "/test.png";                                 // 2650x2260
        final ImageProcessor testProcessor = Utils.openImagePlus(testPath).getProcessor().resize(1325); // 1325x1130

        final int shiftX = 2;
        final int testWidth = testProcessor.getWidth() - shiftX;
        final int testHeight = testProcessor.getHeight();

        // standard layer is tile with right 100 pixels cropped off
        testProcessor.setRoi(0, 0, testWidth, testHeight);
        final FloatProcessor standardLayer = testProcessor.crop().convertToFloatProcessor();
        // final BufferedImage b1 = new ImagePlus("standard", standardLayer).getBufferedImage();

        new ImageJ();
        //new ImagePlus( "standard", standardLayer ).show();;
        //new ImagePlus( "shiftedLayer", shiftedLayer ).show();;

        // SP: look here.  Using 20-29 seems ok, but look at what happens when you change to 23-25.
        final int startShiftZ = 20; // 23
        final int stopShiftZ = 29;  // 25
        final int totalNumberOfLayers = 50;

        // setup layers, shifting subset of them
        Random rnd = new Random( 3434795 );
        double noise = 0;

        final List<FloatProcessor> layers = new ArrayList<>();

        final int movingW = testProcessor.getWidth() - totalNumberOfLayers * 2;

        int offset = 0;
        for (int z = 0; z < totalNumberOfLayers; z++) {

        	if ((z >= startShiftZ) && (z <= stopShiftZ))
        		offset += 2; //1???
        	else
        		offset += 1;
/*
        	if ( z == 15 )
        		noise = 100;
        	else
        		noise = 0;
*/
        	testProcessor.setRoi(offset, 0, movingW, testHeight);

        	final float[] pixels = (float[])testProcessor.crop().convertToFloatProcessor().getPixelsCopy();
        	for ( int i = 0; i < pixels.length; ++i )
        		pixels[ i ] += rnd.nextFloat() * noise - (noise/2.0);
            layers.add( new FloatProcessor( movingW, standardLayer.getHeight(), pixels));
            //layers.add(standardLayer.convertToFloatProcessor());
        }

        final LayerLoader testLayerLoader = new TestLayerLoader(layers);

        // override default correction options here
        final Options inferenceOptions = HeadlessZPositionCorrection.generateDefaultFIBSEMOptions();
        inferenceOptions.minimumSectionThickness = 0.0001;
        //inferenceOptions.regularizationType = RegularizationType.NONE; // IDENTITY leads to min and max not being fixed
        //inferenceOptions.regularizationType = RegularizationType.
        inferenceOptions.comparisonRange = 10; // SP: playing with this value also affects results

        inferenceOptions.scalingFactorRegularizerWeight = 1.0; // cannot correct for noisy slices

        // run Phillip's code
        final double[] transforms =
                HeadlessZPositionCorrection.buildMatrixAndEstimateZCoordinates(inferenceOptions,
                                                                               1,
                                                                               testLayerLoader);

        // print formatted results to stdout (shifted layers flagged with *)

        System.out.printf("Z     Delta Values (* = shifted)%n---   --------------------------%n%03d: %8.4f  ", 0, 0.0);
        int z = 1;
        for (; z < transforms.length; z++) {
            if (z % 10 == 0) {
                System.out.printf("%n%03d: ", z);
            }
            final double delta = transforms[z] - transforms[z-1];
            final String shiftIndicator = (z >= startShiftZ) && (z <= stopShiftZ) ? "*" : " ";
            System.out.printf("%8.4f%s ", delta, shiftIndicator);
        }
        System.out.println();

        System.out.printf("Z     Abs Values (* = shifted)%n---   --------------------------");
        z = 0;
        for (; z < transforms.length; z++) {
            if (z % 10 == 0) {
                System.out.printf("%n%03d: ", z);
            }
            final String shiftIndicator = (z >= startShiftZ) && (z <= stopShiftZ) ? "*" : " ";
            System.out.printf("%8.4f%s ", transforms[z], shiftIndicator);
        }
        System.out.println();
    }

    static class TestLayerLoader implements LayerLoader {
        private final List<FloatProcessor> layers;
        public TestLayerLoader(final List<FloatProcessor> layers) {
            this.layers = layers;
        }
        @Override
        public int getNumberOfLayers() {
            return layers.size();
        }
        @Override
        public FloatProcessor getProcessor(final int layerIndex) {
            return layers.get(layerIndex);
        }
    }

}
