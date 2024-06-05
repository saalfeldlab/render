package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ij.plugin.ImagesToStack;
import org.janelia.alignment.filter.FilterSpec;
import org.junit.Assert;
import org.junit.Test;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Tests the {@link ConfigurableMaskStreakCorrector} class.
 * Main method includes original visualization of Z0422_17_VNC_1_CORRECTOR written by @StephanPreibisch
 *
 * @author Eric Trautman
 */
@SuppressWarnings("JavadocLinkAsPlainText")
public class ConfigurableStreakCorrectorTest {

    public static final int[][] Z0422_17_VNC_1_REGIONS_TO_CLEAR = {
            // x, y, w, h
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

    public static final int[][] TOUGH_RESIN_REGIONS_TO_CLEAR = {
            // x, y, w, h
            {0, 5715, 1000, 20},
            {1000, 5700, 5160, 50}
    };

    public static final ConfigurableMaskStreakCorrector TOUGH_RESIN_CORRECTOR =
            new ConfigurableMaskStreakCorrector(8,
                                                6161,
                                                11440,
                                                0,
                                                0,
                                                TOUGH_RESIN_REGIONS_TO_CLEAR);

    /**
     * Source images for each imaging experiment were created like this:
     * <pre>
     * TILE_ID="23-10-23_084007_0-0-0.2177.0"
     * PROJECT_URL="http://renderer.int.janelia.org:8080/render-ws/v1/owner/fibsem/project/jrc_tough_resin_RD_part2"
     * TILE_URL="${PROJECT_URL}/stack/v1_acquire/tile/${TILE_ID}/tiff-image?excludeMask=true&excludeAllTransforms=true"
     * curl -o src/${TILE_ID}.tif "${TILE_URL}"
     * </pre>
     */
    public static final String[] TOUGH_RESIN_FILE_NAMES = {
            "23-10-13_000031_0-0-0.213.0.tif",  // 3nA_3p0MHz, region might be a little too tall in y
            "23-10-14_113315_0-0-0.848.0.tif",  // 3nA_2p0MHz, region might be a little too tall in y
            "23-10-16_080039_0-0-0.1424.0.tif", // 3nA_1p0MHz, skipped a couple of smaller bands
            "23-10-19_053141_0-0-0.1881.0.tif", // 3nA_0p5MHz, skipped a couple of smaller bands
            "23-10-23_084007_0-0-0.2177.0.tif", // 2nA_0p5MHz, skipped a couple of smaller bands
            "23-10-26_081956_0-0-0.2373.0.tif", // 2nA_1p0MHz, skipped a couple of smaller bands
            "23-10-30_081056_0-0-0.3003.0.tif", // 2nA_2p0MHz, region might be a little too tall in y
            "23-11-01_072630_0-0-0.3598.0.tif"  // 2nA_3p0MHz, region might be a little too tall in y
    };

    public static final int[][] HUM_AIRWAY_REGIONS_TO_CLEAR = {
            // x, y, w, h
            {0, 4089, 5200, 12},
            {5200, 4089, 500, 12},
            {5700, 4085, 202, 20},
            {5902, 4091, 186, 8},
            {6088, 4093, 71, 4}
    };

    public static final ConfigurableMaskStreakCorrector HUM_AIRWAY_CORRECTOR =
            new ConfigurableMaskStreakCorrector(8,
                                                6161,
                                                8190,
                                                0,
                                                0,
                                                HUM_AIRWAY_REGIONS_TO_CLEAR);

    public static final SmoothMaskStreakCorrector SMOOTH_MASK_STREAK_CORRECTOR =
            new SmoothMaskStreakCorrector(8,
                                          6161,
                                          8190,
                                          30,
                                          15,
                                          0.0);

    public static final String[] HUM_AIRWAY_FILE_NAMES = {
            "23-12-04_185805_0-0-0.4664.0.tif",
            "23-12-04_185805_0-0-1.4664.0.tif",
            "23-12-04_185805_0-0-2.4664.0.tif"
    };

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

    @SuppressWarnings({"ConstantConditions"})
    public static void main(final String[] args) {

        new ImageJ();

        // to get suitable images for testing:
        // - Copy imageURL (from TileSpec) into FIJI > File > Import > HDF5/N5/Zarr/OME-NGFF ...
        // - Select correct dataset (choose highest mipmap level)
        // - Save image as png/tiff
        final String srcPath = "/Users/trautmane/Desktop/cellmap_cosem/jrc_hum-airway-14953vc/raw-images/" +
                               HUM_AIRWAY_FILE_NAMES[0]; // change file names index to test different images

        final Map<String, Double> parameters = new HashMap<>();
        parameters.put("numThreads", 8.0);
        parameters.put("fftWidth", 5545.0);
        parameters.put("fftHeight", 10920.0);
        parameters.put("innerCutoff", 18.0);
        parameters.put("bandWidth", 8.0);
        parameters.put("angle", 0.0);
        parameters.put("gaussianBlurRadius", 20.0);
        parameters.put("initialThreshold", 7.0);
        parameters.put("finalThreshold", 0.05);

        displayParameterRange(srcPath, parameters, "innerCutoff", 3.0, 3, false);
        displayParameterRange(srcPath, parameters, "bandWidth", 2.0, 3, false);
        // displayParameterRange(srcPath, parameters, "angle", 0.0, 1.0, 3, false);
        displayParameterRange(srcPath, parameters, "gaussianBlurRadius", 20.0, 3, true);
        displayParameterRange(srcPath, parameters, "initialThreshold", 1.0, 3, true);
        displayParameterRange(srcPath, parameters, "finalThreshold", 0.01, 3, true);

        // this shows the final result of the correction process as well as the end result
        displayParameterRange(srcPath, parameters, "numThreads", 1.0, 0, true);
        final ImagePlus imp = new ImagePlus(srcPath);
        imp.setTitle("Original");
        imp.show();
        // displayStreakCorrectionDetails(srcPath, HUM_AIRWAY_CORRECTOR);
    }

    /**
     * Displays a range of parameter values for a given parameter. The parameter values are centered around the midpoint
     * (the pre-set parameter value) and steps are taken in both directions from the midpoint. The results are displayed
     * in a stack.
     *
     * @param srcPath the path to the source image.
     * @param parameters a map of parameters for both {@link SmoothMaskStreakCorrector} and {@link LocalSmoothMaskStreakCorrector}.
     * @param parameterToVary the name of the parameter to vary.
     * @param stepsize the size of the steps to take in both directions from the midpoint.
     * @param steps the number of steps to take in both directions from the midpoint.
     * @param localizeCorrection whether to use {@link LocalSmoothMaskStreakCorrector} or not.
     */
    private static void displayParameterRange(
            final String srcPath,
            final Map<String, Double> parameters,
            final String parameterToVary,
            final double stepsize,
            final int steps,
            final boolean localizeCorrection) {

        final int n = 2 * steps + 1;
        final List<ImagePlus> images = new ArrayList<>(n);
        final double midpoint = parameters.get(parameterToVary);
        final double start = midpoint - steps * stepsize;
        final double end = midpoint + steps * stepsize;
        for (double val = start; val <= end; val += stepsize) {
            parameters.put(parameterToVary, val);
            final SmoothMaskStreakCorrector globalCorrector = new SmoothMaskStreakCorrector(
                    parameters.get("numThreads").intValue(),
                    parameters.get("fftWidth").intValue(),
                    parameters.get("fftHeight").intValue(),
                    parameters.get("innerCutoff").intValue(),
                    parameters.get("bandWidth").intValue(),
                    parameters.get("angle"));

            final ImagePlus result;
            if (localizeCorrection) {
                final StreakCorrector corrector = new LocalSmoothMaskStreakCorrector(
                        globalCorrector,
                        parameters.get("gaussianBlurRadius").intValue(),
                        parameters.get("initialThreshold").floatValue(),
                        parameters.get("finalThreshold").floatValue());
                result = computeStreakCorrectionResult(srcPath, corrector);
            } else {
                final StreakCorrector corrector = new LocalSmoothMaskStreakCorrector(
                        globalCorrector,
                        parameters.get("gaussianBlurRadius").intValue(),
                        parameters.get("initialThreshold").floatValue(),
                        parameters.get("finalThreshold").floatValue());
                result = computeStreakCorrectionResult(srcPath, corrector);
            }
            images.add(result);
        }

        final ImagePlus stack = ImagesToStack.run(images.toArray(new ImagePlus[0]));
        stack.setTitle("Varying " + parameterToVary);
        stack.show();

        parameters.put(parameterToVary, midpoint);
    }

    private static ImagePlus computeStreakCorrectionResult(final String srcPath, final StreakCorrector corrector) {
        final ImagePlus imp = new ImagePlus(srcPath);
        corrector.process(imp.getProcessor(), 1.0);
        imp.setTitle(corrector.toParametersMap().toString());
        return imp;
    }

    // this shows all steps of the correction process
    // NOTE: this can only be used for ConfigurableMaskStreakCorrector as some of its logic is duplicated here
    private static void displayStreakCorrectionDetails(final String srcPath, final StreakCorrector corrector) {
        final ImagePlus imp = new ImagePlus(srcPath);
        imp.setProcessor(imp.getProcessor().convertToFloat());

        // original code from Preibisch that displays correction data
        final Img<FloatType> img = ImageJFunctions.wrapFloat(imp);

        ImageJFunctions.show(img).setTitle("input");
        final double avg = StreakCorrector.avgIntensity(img);
        System.out.println(avg);

        // show FFT and bandpass images to manually find clear region rectangles for filtering
        // (choose Macro > Record to see the numbers)
        final boolean showFFTAndBandpass = true;

        // remove streaking (but it'll introduce a wave pattern)
        final Img<FloatType> imgCorr = corrector.fftBandpassCorrection(img, showFFTAndBandpass);

        // create the wave pattern introduced by the filtering above
        final Img<FloatType> patternCorr = corrector.createPattern(imgCorr.dimensionsAsLongArray(), avg);

        // removes the wave pattern from the corrected image
        final RandomAccessibleInterval<UnsignedByteType> fixed =
                Converters.convertRAI(imgCorr,
                                      patternCorr,
                                      (i1,i2,o) -> o.set(Math.max(0, Math.min(255, Math.round(i1.get() - i2.get())))),
                                      new UnsignedByteType());

        ImageJFunctions.show(imgCorr).setTitle("imgCorr");
        ImageJFunctions.show(patternCorr).setTitle("patternCorr");
        ImageJFunctions.show(fixed).setTitle("fixed");
    }

    public static String getToughResinPath(final int fileNameIndex) {
        return "/Users/trautmane/Desktop/bleck-streak/src/" + TOUGH_RESIN_FILE_NAMES[fileNameIndex];
    }
}
