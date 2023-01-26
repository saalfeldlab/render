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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.util.Timer;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.transform.SEMDistortionTransformA;
import org.janelia.alignment.util.LogbackTestTools;
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
        //noinspection CommentedOutCode
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

    @SuppressWarnings({"ConstantConditions", "CommentedOutCode"})
    public static void findBestScanCorrectionParameters()
            throws IOException {

        final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();
        parameters.renderWeb = new RenderWebServiceParameters();
        parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.renderWeb.owner = "reiser";
        parameters.renderWeb.project = "Z0422_05_Ocellar";
        parameters.stack = "v3_acquire";

        parameters.featureRenderClip.clipWidth = 1200;  // full scale clip pixels
        parameters.featureRenderClip.clipHeight = 1200; // full scale clip pixels

        parameters.scale = 0.25;

        // http://renderer.int.janelia.org:8080/ng/#!%7B%22dimensions%22:%7B%22x%22:%5B8e-9%2C%22m%22%5D%2C%22y%22:%5B8e-9%2C%22m%22%5D%2C%22z%22:%5B8e-9%2C%22m%22%5D%7D%2C%22position%22:%5B-163.37060546875%2C-4313.3564453125%2C1263.5%5D%2C%22crossSectionScale%22:2%2C%22projectionScale%22:32768%2C%22layers%22:%5B%7B%22type%22:%22image%22%2C%22source%22:%7B%22url%22:%22render://http://renderer.int.janelia.org:8080/reiser/Z0422_05_Ocellar/v3_acquire_align%22%2C%22subsources%22:%7B%22default%22:true%2C%22bounds%22:true%7D%2C%22enableDefaultSubsources%22:false%7D%2C%22tab%22:%22source%22%2C%22name%22:%22v3_acquire_align%22%7D%5D%2C%22selectedLayer%22:%7B%22layer%22:%22v3_acquire_align%22%7D%2C%22layout%22:%22xy%22%7D

        final int numberOfPairsToVisualize = 0; // change to 1 to see original pair (or more to see test pairs)
        final int checkPeaks = 50;
        final boolean subpixelAccuracy = true;
        final int maxTestsToRun = 1000;
        final int maxNumberOfRuns = 6;

        // column 0, top relative position => crop and view bottom edge of tiles
        final CanvasId p = new CanvasId("", "22-06-17_080526_0-0-0.1263.0", MontageRelativePosition.LEFT);
        final CanvasId q = new CanvasId("", "22-06-17_081143_0-0-1.1264.0", MontageRelativePosition.RIGHT);

        // tile ids for next problem area (z 2097 to 2098), z 2098 is patched, so really z 2099
        // final CanvasId p = new CanvasId("", "22-06-18_034043_0-0-0.2097.0", MontageRelativePosition.LEFT);
        // final CanvasId q = new CanvasId("", "22-06-18_125654_0-0-1.patch.2098.0", MontageRelativePosition.RIGHT);

        final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);

        if (numberOfPairsToVisualize > 0) {
            // TODO: Preibisch - change this to your Fiji plugins directory so that stitching plugin is available
            System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
            new ImageJ();
        }

        final double[] originalParameters = { 19.4, 64.8, 24.4, 972.0 };
        final double[] stepSizes = {10.0, 5.0, 2.5, 1.25, 0.625, 0.3125, 0.15625,
                                    0.078125, 0.0390625, 0.01953125, 0.009765625};

        // hide all logging except from this test class
        LogbackTestTools.setRootLogLevelToError();
        LogbackTestTools.setLogLevelToInfo(LOG.getName()); // another option: setLogLevelToInfoToDebug

        final Timer timer = new Timer();
        timer.start();

        int testCountForAllRuns = 0;
        String bestDataString = "";
        Tester tester = null;

        for (int i = 0; i < maxNumberOfRuns; i++) {
            LOG.info("\n\n");
            LOG.info("findBestScanCorrectionParameters: begin run {} of {}\n", (i+1), maxNumberOfRuns);

            tester = new Tester(client,
                                parameters.scale,
                                p,
                                q,
                                checkPeaks,
                                subpixelAccuracy,
                                numberOfPairsToVisualize,
                                originalParameters,
                                stepSizes,
                                maxTestsToRun);

            bestDataString = tester.optimizeTransformParametersForAllSteps();

            final String[] parameterStrings = bestDataString.split(" ");
            for (int parameterIndex = 0; parameterIndex < originalParameters.length; parameterIndex++) {
                originalParameters[parameterIndex] = Double.parseDouble(parameterStrings[parameterIndex]);
            }

            testCountForAllRuns += tester.totalTestCount;
        }

        LOG.info("findBestScanCorrectionParameters: done, ran {} tests in {} seconds, best parameters are {} with {}",
                 testCountForAllRuns,
                 (int) (timer.stop() / 1000),
                 bestDataString,
                 tester.resultToString(tester.bestResult));

        if (numberOfPairsToVisualize > 0) {
            SimpleMultiThreading.threadHaltUnClean();
        }
    }

    private static class Tester {
        private final RenderTileWithTransformsClient client;
        private final double renderScale;
        private final CanvasId pCanvasId;
        private final TileSpec pTileSpec;
        private final CanvasId qCanvasId;
        private final TileSpec qTileSpec;
        private final StitchingParameters stitchingParameters;
        private final int numberOfPairsToVisualize;
        private final double[] originalParameters;
        private final double[] stepSizes;
        private final int maxNumberOfTests;

        private PairWiseStitchingResult bestResult;
        private String bestTransformDataString;
        private final Set<String> testedDataStrings;

        /** Current test's (4) transform coefficient parameters. */
        private double[] currentTestTransformValues;

        /**
         * Flag indicating whether an improved parameter has been found in the current step
         * for each of the (4) transform coefficient parameters
         */
        private int totalTestCount;

        public Tester(final RenderTileWithTransformsClient client,
                      final double renderScale,
                      final CanvasId pCanvasId,
                      final CanvasId qCanvasId,
                      final int checkPeaks,
                      final boolean subpixelAccuracy,
                      final int numberOfPairsToVisualize,
                      final double[] originalParameters,
                      final double[] stepSizes,
                      final int maxNumberOfTests)
                throws IOException {

            this.client = client;
            this.renderScale = renderScale;
            this.pCanvasId = pCanvasId;
            this.pTileSpec = client.fetchTileSpec(pCanvasId.getId());
            this.qCanvasId = qCanvasId;
            this.qTileSpec = client.fetchTileSpec(qCanvasId.getId());

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

            this.numberOfPairsToVisualize = numberOfPairsToVisualize;
            this.originalParameters = originalParameters;
            this.stepSizes = stepSizes;
            this.maxNumberOfTests = maxNumberOfTests;
            this.testedDataStrings = new HashSet<>();

            this.currentTestTransformValues = new double[originalParameters.length];
        }

        public String optimizeTransformParametersForAllSteps() {

            final String originalTransformDataString = buildTransformDataString(originalParameters);
            this.testedDataStrings.clear();
            this.testedDataStrings.add(originalTransformDataString);

            this.bestResult = deriveStitchingResult(originalTransformDataString, 0);
            this.bestTransformDataString = originalTransformDataString;
            this.currentTestTransformValues = originalParameters.clone();
            this.totalTestCount = 0;

            for (final double stepSize : stepSizes) {

                LOG.info("---------------------------------");
                LOG.info("optimizeTransformParametersForAllSteps: begin stepSize {}, best parameters are {} with {}",
                         stepSize,
                         bestTransformDataString,
                         resultToString(bestResult));
                LOG.info("---------------------------------");

                // randomly order parameter optimization for each step
                final List<Integer> transformParameterIndexes =
                        IntStream.range(0, originalParameters.length).boxed().collect(Collectors.toList());
                Collections.shuffle(transformParameterIndexes);

                for (final int indexOfTransformParameterToChange : transformParameterIndexes) {
                    optimizeTransformParameterForStep(indexOfTransformParameterToChange,
                                                      stepSize);
                }

                if (totalTestCount >= maxNumberOfTests) {
                    break;
                }
            }

            LOG.info("optimizeTransformParametersForAllSteps: after {} tests, best parameters are {} with {}",
                     totalTestCount,
                     bestTransformDataString,
                     resultToString(bestResult));

            return bestTransformDataString;
        }

        private void optimizeTransformParameterForStep(final int indexOfTransformParameterToChange,
                                                       final double stepSize) {

            if (totalTestCount < maxNumberOfTests) {
                boolean isUpBetter = true;
                boolean isDownBetter = true;
                do {

                    if (isUpBetter) {
                        // test another step up if previous step up was better (or this is the first test)
                        isUpBetter = runOneTest(indexOfTransformParameterToChange, stepSize);
                    }
                    if (isDownBetter) {
                        // test another step down if previous step down was better (or this is the first test)
                        isDownBetter = runOneTest(indexOfTransformParameterToChange, -stepSize); // test step down
                    }

                    // update current parameter value if better result was found
                    // must check isDownBetter first in case both up and down are better
                    if (isDownBetter) {
                        currentTestTransformValues[indexOfTransformParameterToChange] -= stepSize;
                    } else if (isUpBetter) {
                        currentTestTransformValues[indexOfTransformParameterToChange] += stepSize;
                    }
                } while ((isUpBetter || isDownBetter) && (totalTestCount < maxNumberOfTests));
            }

            LOG.info("optimizeTransformParameterForStep: for parameter {} and step {}, best parameters are {} with {}, totalTestCount is {}",
                     indexOfTransformParameterToChange,
                     stepSize,
                     bestTransformDataString,
                     resultToString(bestResult),
                     totalTestCount);
        }

        private boolean runOneTest(final int indexOfTransformParameterToChange,
                                   final double stepSize) {

            boolean foundBetterResult = false;
            totalTestCount++;

            final double[] testValues = currentTestTransformValues.clone();
            testValues[indexOfTransformParameterToChange] = testValues[indexOfTransformParameterToChange] + stepSize;

            final String testDataString = buildTransformDataString(testValues);

            if (testedDataStrings.contains(testDataString)) {
                LOG.info("runOneTest: already tested {}", testDataString);
            } else {

                final PairWiseStitchingResult testResult = deriveStitchingResult(testDataString,
                                                                                 testedDataStrings.size());
                testedDataStrings.add(testDataString);

                if (testResult.getCrossCorrelation() > bestResult.getCrossCorrelation()) {
                    bestResult = testResult;
                    bestTransformDataString = testDataString;
                    foundBetterResult = true;
                }

                final String betterOrWorse = foundBetterResult ? "better" : "worse";
                LOG.info("runOneTest: {} for parameters {} with {}",
                         betterOrWorse,
                         testDataString,
                         resultToString(testResult));
            }

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

        private PairWiseStitchingResult deriveStitchingResult(final String transformDataString,
                                                              final int numberOfPairsTested) {

            final LeafTransformSpec transformSpec = new LeafTransformSpec(SEMDistortionTransformA.class.getName(),
                                                                          transformDataString);
            final List<TransformSpec> tileTransformSpecList = Collections.singletonList(transformSpec);

            final ImagePlus pTilePlus = renderTile(pTileSpec, tileTransformSpecList, transformDataString, pCanvasId);
            final ImagePlus qTilePlus = renderTile(qTileSpec, tileTransformSpecList, transformDataString, qCanvasId);

            // stitch pair ...
            final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise(pTilePlus,
                                                                                          qTilePlus,
                                                                                          null,
                                                                                          null,
                                                                                          1,
                                                                                          1,
                                                                                          stitchingParameters);

            LOG.debug("deriveStitchingResult: {} for parameters {}", resultToString(result), transformDataString);

            if (numberOfPairsTested < numberOfPairsToVisualize) {
                pTilePlus.show();
                qTilePlus.show();
            }

            return result;
        }

        private String resultToString(final PairWiseStitchingResult result) {
            return "crossCorrelation " + result.getCrossCorrelation() +
                   ", phaseCorrelation " + result.getPhaseCorrelation() +
                   ", offset " + Arrays.toString(result.getOffset());
        }

        private ImagePlus renderTile(final TileSpec tileSpec,
                                     final List<TransformSpec> tileTransforms,
                                     final String dataString,
                                     final CanvasId canvasId) {
            final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                    client.renderTile(tileSpec, tileTransforms, renderScale, canvasId, null);
            final ImageProcessor croppedTile = quickCropMaskedArea(ipwm);
            return new ImagePlus(tileSpec.getTileId() + " " + dataString, croppedTile);
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
