package org.janelia.render.client.tile;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.transform.SEMDistortionTransformA;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link RenderTileWithTransformsClient} class.
 *
 * @author Eric Trautman
 */
public class RenderTileWithTransformsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RenderTileWithTransformsClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
//            final String[] testArgs = {
//                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                    "--owner", "reiser",
//                    "--project", "Z0422_05_Ocellar",
//                    "--stack", "v3_acquire",
//                    "--rootDirectory", "/Users/trautmane/Desktop/fibsem_scan_correction",
//                    "--tileId", "22-06-17_080526_0-0-1.1263.0"
//            };
//
//            RenderTileWithTransformsClient.main(testArgs);

            findBestScanCorrectionParameters();
            
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    public static void findBestScanCorrectionParameters()
            throws IOException {

        final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();
        parameters.renderWeb = new RenderWebServiceParameters();
        parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.renderWeb.owner = "reiser";
        parameters.renderWeb.project = "Z0422_05_Ocellar";
        parameters.stack = "v3_acquire";

        parameters.featureRenderClip.clipWidth = 2000;  // full scale clip pixels
        parameters.featureRenderClip.clipHeight = 1000; // full scale clip pixels

        parameters.scale = 0.25;

        // http://renderer.int.janelia.org:8080/ng/#!%7B%22dimensions%22:%7B%22x%22:%5B8e-9%2C%22m%22%5D%2C%22y%22:%5B8e-9%2C%22m%22%5D%2C%22z%22:%5B8e-9%2C%22m%22%5D%7D%2C%22position%22:%5B-163.37060546875%2C-4313.3564453125%2C1263.5%5D%2C%22crossSectionScale%22:2%2C%22projectionScale%22:32768%2C%22layers%22:%5B%7B%22type%22:%22image%22%2C%22source%22:%7B%22url%22:%22render://http://renderer.int.janelia.org:8080/reiser/Z0422_05_Ocellar/v3_acquire_align%22%2C%22subsources%22:%7B%22default%22:true%2C%22bounds%22:true%7D%2C%22enableDefaultSubsources%22:false%7D%2C%22tab%22:%22source%22%2C%22name%22:%22v3_acquire_align%22%7D%5D%2C%22selectedLayer%22:%7B%22layer%22:%22v3_acquire_align%22%7D%2C%22layout%22:%22xy%22%7D

        final boolean visualizeTiles = false;
        final int checkPeaks = 50;
        final boolean subpixelAccuracy = true;

        // column 0, top relative position => crop and view bottom edge of tiles
        final CanvasId p = new CanvasId("", "22-06-17_080526_0-0-0.1263.0", MontageRelativePosition.TOP);
        final CanvasId q = new CanvasId("", "22-06-17_081143_0-0-0.1264.0", MontageRelativePosition.TOP);

        // tile ids for next problem area (z 2097 to 2098), z 2098 is patched, so really z 2099
//        final CanvasId p = new CanvasId("", "22-06-18_034043_0-0-0.2097.0", MontageRelativePosition.LEFT);
//        final CanvasId q = new CanvasId("", "22-06-18_125654_0-0-0.patch.2098.0", MontageRelativePosition.LEFT);

        final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);

        if (visualizeTiles) {
            // TODO: Preibisch - change this to your Fiji plugins directory so that stitching plugin is available
            System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
            new ImageJ();
        }

        final double[] originalParameters = { 19.4, 64.8, 24.4, 972.0 };
        final double[] stepSizes = {10.0, 5.0, 2.5, 1.25, 0.625, 0.3125, 0.15625,
                                    0.078125, 0.0390625, 0.01953125, 0.009765625};

        final Tester tester = new Tester(client,
                                         parameters.scale,
                                         p,
                                         q,
                                         checkPeaks,
                                         subpixelAccuracy,
                                         visualizeTiles,
                                         originalParameters,
                                         stepSizes);

        tester.runAllTests(500);

        if (visualizeTiles) {
            SimpleMultiThreading.threadHaltUnClean();
        }
    }

    private static class Tester {
        private final RenderTileWithTransformsClient client;
        private final double renderScale;
        private final CanvasId pCanvasId;
        private final CanvasId qCanvasId;
        private final StitchingParameters stitchingParameters;
        private final boolean visualizeTiles;
        private final double[] originalParameters;
        private final double[] stepSizes;

        private PairWiseStitchingResult bestResult;
        private String bestTransformDataString;
        private final Set<String> testedDataStrings;

        /** Current test's (4) transform coefficient parameters. */
        private final double[] currentTestTransformValues;

        /** Current step index for each of the (4) transform coefficient parameters. */
        private int[] currentStepSizeIndexes;

        /**
         * Flag indicating whether an improved parameter has been found
         * for each of the (4) transform coefficient parameters
         */
        private boolean[] foundSomethingBetter;
        private int totalTestCount;

        public Tester(final RenderTileWithTransformsClient client,
                      final double renderScale,
                      final CanvasId pCanvasId,
                      final CanvasId qCanvasId,
                      final int checkPeaks,
                      final boolean subpixelAccuracy,
                      final boolean visualizeTiles,
                      final double[] originalParameters,
                      final double[] stepSizes) {

            this.client = client;
            this.renderScale = renderScale;
            this.pCanvasId = pCanvasId;
            this.qCanvasId = qCanvasId;

            this.stitchingParameters = new StitchingParameters();
            // static parameters
            this.stitchingParameters.dimensionality = 2;
            this.stitchingParameters.fusionMethod = 0;
            this.stitchingParameters.fusedName = "";
            this.stitchingParameters.addTilesAsRois = false;
            this.stitchingParameters.computeOverlap = true;
            this.stitchingParameters.ignoreZeroValuesFusion = false;
            this.stitchingParameters.downSample = false;
            this.stitchingParameters.displayFusion = false;
            this.stitchingParameters.invertX = false;
            this.stitchingParameters.invertY = false;
            this.stitchingParameters.ignoreZStage = false;
            this.stitchingParameters.xOffset = 0.0;
            this.stitchingParameters.yOffset = 0.0;
            this.stitchingParameters.zOffset = 0.0;
            // dynamic parameters
            this.stitchingParameters.checkPeaks = checkPeaks;
            this.stitchingParameters.subpixelAccuracy = subpixelAccuracy;

            this.visualizeTiles = visualizeTiles;
            this.originalParameters = originalParameters;
            this.stepSizes = stepSizes;
            this.testedDataStrings = new HashSet<>();

            this.currentTestTransformValues = new double[originalParameters.length];
        }

        public void runAllTests(final int maxNumberOfTests)
                throws IOException {

            final String originalTransformDataString = buildTransformDataString(originalParameters);
            this.testedDataStrings.clear();
            this.testedDataStrings.add(originalTransformDataString);

            this.bestResult = deriveStitchingResult(originalTransformDataString);
            this.bestTransformDataString = originalTransformDataString;

            System.arraycopy(originalParameters, 0,
                             this.currentTestTransformValues, 0,
                             originalParameters.length);

            this.currentStepSizeIndexes = new int[originalParameters.length]; // defaulted to 0
            this.foundSomethingBetter = new boolean[originalParameters.length]; // defaulted to false
            this.totalTestCount = 0;

            final Random random = new Random();
            boolean foundBetterResult;
            do {

                // randomly pick [0...3] (one of the parameters)
                final int changeIndex = random.nextInt(4);

                // test step up
                foundBetterResult = runOneTest(changeIndex, 1);

                if (! foundBetterResult) {
                    // test step down
                    foundBetterResult = runOneTest(changeIndex, -1);
                }

                if (foundBetterResult) {
                    foundSomethingBetter[changeIndex] = true;
                } else {
                    // if none was better, reduce step size
                    if (currentStepSizeIndexes[changeIndex] < stepSizes.length - 1) {
                        currentStepSizeIndexes[changeIndex]++;
                    }
                    // TODO: not sure about this
                    foundSomethingBetter[changeIndex] = false;
                }

                if (totalTestCount >= maxNumberOfTests) {
                    break;
                }

            } while (anyRemainingStepSizes() || anyUnimprovedParameters());

            LOG.info("runAllTests: after {} tests, best parameters are {} with {}, foundSomethingBetter {}, currentStepSizeIndexes {} with {} steps",
                     totalTestCount,
                     bestTransformDataString,
                     resultToString(bestResult),
                     Arrays.toString(foundSomethingBetter),
                     Arrays.toString(currentStepSizeIndexes),
                     stepSizes.length);
        }

        private boolean anyRemainingStepSizes() {
            boolean anyRemaining = false;
            final int lastStepSizeIndex = stepSizes.length - 1;
            for (final int index : currentStepSizeIndexes) {
                if (index != lastStepSizeIndex) {
                    anyRemaining = true;
                    break;
                }
            }
            return anyRemaining;
        }

        private boolean anyUnimprovedParameters() {
            boolean anyUnimproved = false;
            for (final boolean foundBetter : foundSomethingBetter) {
                if (! foundBetter) {
                    anyUnimproved = true;
                    break;
                }
            }
            return anyUnimproved;
        }

        private boolean runOneTest(final int indexOfTransformParameterToChange,
                                   final int stepFactor)
                throws IOException {

            boolean foundBetterResult = false;

            final double stepValue = stepSizes[currentStepSizeIndexes[indexOfTransformParameterToChange]] * stepFactor;
            final double[] testValues = new double[currentTestTransformValues.length];
            System.arraycopy(currentTestTransformValues, 0,
                             testValues, 0,
                             currentTestTransformValues.length);
            testValues[indexOfTransformParameterToChange] = testValues[indexOfTransformParameterToChange] + stepValue;

            final String testDataString = buildTransformDataString(testValues);

            if (testedDataStrings.contains(testDataString)) {

                LOG.info("runTest: already tested {}", testDataString);

            } else {

                final PairWiseStitchingResult testResult = deriveStitchingResult(testDataString);
                testedDataStrings.add(testDataString);

                if (testResult.getCrossCorrelation() > bestResult.getCrossCorrelation()) {
                    final double improvement = testResult.getCrossCorrelation() - bestResult.getCrossCorrelation();
                    LOG.info("runTest: changing parameter {} from {} to {} improved crossCorrelation by {}",
                             indexOfTransformParameterToChange,
                             currentTestTransformValues[indexOfTransformParameterToChange],
                             testValues[indexOfTransformParameterToChange],
                             improvement);
                    bestResult = testResult;
                    bestTransformDataString = testDataString;
                    currentTestTransformValues[indexOfTransformParameterToChange] =
                            testValues[indexOfTransformParameterToChange];
                    foundBetterResult = true;
                }
            }

            totalTestCount++;

            return foundBetterResult;
        }

        private String buildTransformDataString(final double[] transformationParameters) {
            final StringBuilder dataStringBuilder = new StringBuilder();
            for (final double p : transformationParameters) {
                dataStringBuilder.append(p).append(" ");
            }
            dataStringBuilder.append("0"); // last 0 = x dimension
            return dataStringBuilder.toString();
        }

        private PairWiseStitchingResult deriveStitchingResult(final String transformDataString)
                throws IOException {

            final LeafTransformSpec transformSpec = new LeafTransformSpec(SEMDistortionTransformA.class.getName(),
                                                                          transformDataString);
            final List<TransformSpec> tileTransformSpecList = Collections.singletonList(transformSpec);

            final ImagePlus pTilePlus = renderTile(tileTransformSpecList, transformDataString, pCanvasId);
            final ImagePlus qTilePlus = renderTile(tileTransformSpecList, transformDataString, qCanvasId);

            // stitch pair ...
            final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise(pTilePlus,
                                                                                          qTilePlus,
                                                                                          null,
                                                                                          null,
                                                                                          1,
                                                                                          1,
                                                                                          stitchingParameters);

            LOG.info("deriveStitchingResult: {} for parameters {}", resultToString(result), transformDataString);

            if (visualizeTiles) {
                pTilePlus.show();
                qTilePlus.show();
            }

            return result;
        }

        private String resultToString(final PairWiseStitchingResult result) {
            return "crossCorrelation " + result.getCrossCorrelation() +
                   ", phaseCorrelation " + result.getCrossCorrelation() +
                   ", offset " + Arrays.toString(result.getOffset());
        }

        private ImagePlus renderTile(final List<TransformSpec> tileTransforms,
                                     final String dataString,
                                     final CanvasId canvasId)
                throws IOException {
            final String tileId = canvasId.getId();
            final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                    client.renderTile(tileId, tileTransforms, renderScale, canvasId, null);
            final ImageProcessor croppedTile = quickCropMaskedArea(ipwm);
            return new ImagePlus(tileId + " " + dataString, croppedTile);
        }

    }

    /**
     * @return image processor with masked area cropped away
     *         ("quick" hack looks for first unmasked pixel and crops rectangle from there)
     */
    public static ImageProcessor quickCropMaskedArea(final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm) {

        final ImageProcessor croppedTile;

        if (ipwm.mask != null) {

            Integer cropX = null;
            int cropY = 0;

            // find first non-zero intensity pixel and crop from there
            for (int y = 0; y < ipwm.getHeight(); y++) {
                for (int x = 0; x < ipwm.getWidth(); x++) {
                    final int i = ipwm.mask.get(x, y);
                    if ((i == 255) && (cropX == null)) { // let's look for the first unmasked, non-interpolated pixel
                        cropX = x;
                        cropY = y;
                        break;
                    }
                }
            }

            if (cropX == null) {
                cropX = 0;
            }

            final int cropWidth = ipwm.getWidth() - cropX;
            final int cropHeight = ipwm.getHeight() - cropY;

            final Rectangle roi = new Rectangle(cropX, cropY, cropWidth, cropHeight);
            ipwm.ip.setRoi(roi);
            croppedTile = ipwm.ip.crop();

        } else {
            croppedTile = ipwm.ip;
        }

        return croppedTile;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTileWithTransformsClientTest.class);
}
