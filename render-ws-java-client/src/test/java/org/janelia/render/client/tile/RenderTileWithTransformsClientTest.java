package org.janelia.render.client.tile;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.ImagesToStack;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.TranslationModel2D;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.stitching.fusion.Fusion;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.util.Timer;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.OrderedCanvasIdPair;
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

import net.imglib2.type.numeric.integer.UnsignedByteType;
import plugin.Stitching_Pairwise;

/**
 * Tests the {@link RenderTileWithTransformsClient} class.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"ConstantConditions", "CommentedOutCode", "unused"})
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

            final boolean visualizeResult = true;
            debugOcellarScanCorrection(visualizeResult);
//            visualizeOcellarScanCorrection();

        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    public static List<OrderedCanvasIdPair> getTilePairsForOcellarProblemAreas() {

        // first problem area in neuroglancer:
        //   http://renderer.int.janelia.org:8080/ng/#!%7B%22dimensions%22:%7B%22x%22:%5B8e-9%2C%22m%22%5D%2C%22y%22:%5B8e-9%2C%22m%22%5D%2C%22z%22:%5B8e-9%2C%22m%22%5D%7D%2C%22position%22:%5B-163.37060546875%2C-4313.3564453125%2C1263.5%5D%2C%22crossSectionScale%22:2%2C%22projectionScale%22:32768%2C%22layers%22:%5B%7B%22type%22:%22image%22%2C%22source%22:%7B%22url%22:%22render://http://renderer.int.janelia.org:8080/reiser/Z0422_05_Ocellar/v3_acquire_align%22%2C%22subsources%22:%7B%22default%22:true%2C%22bounds%22:true%7D%2C%22enableDefaultSubsources%22:false%7D%2C%22tab%22:%22source%22%2C%22name%22:%22v3_acquire_align%22%7D%5D%2C%22selectedLayer%22:%7B%22layer%22:%22v3_acquire_align%22%7D%2C%22layout%22:%22xy%22%7D

        // TODO: comment/uncomment pairs based upon what you want to process
        //noinspection ArraysAsListWithZeroOrOneArgument
        return Arrays.asList(
                // tile ids for first problem area (z 1263 to 1264)
                //   for column 0 tile, left relative position means crop and view right edge of tile
//                new OrderedCanvasIdPair(
//                        new CanvasId("", "22-06-17_080526_0-0-0.1263.0", MontageRelativePosition.LEFT),
//                        new CanvasId("", "22-06-17_081143_0-0-1.1264.0", MontageRelativePosition.RIGHT),
//                        1.0)
//                ,
                // tile ids for next problem area (z 2097 to 2098), z 2098 is patched, so really z 2099
                new OrderedCanvasIdPair(
                        new CanvasId("", "22-06-18_034043_0-0-0.2097.0", MontageRelativePosition.LEFT),
                        new CanvasId("", "22-06-18_125654_0-0-1.patch.2098.0", MontageRelativePosition.RIGHT),
                        1.0)
        );
    }

    /** Threshold for minimum correlation improvement to consider a scan parameter change "better". */
    private static final double CORRELATION_THRESHOLD = 0.0000001;

    /** Threshold to flag correlation/stitch result as unreliable (looking at difference from original stitch offset). */
    private static final float OFFSET_THRESHOLD = 5.0f;

    /** Set this to null to disable logging to files or set it to a valid path if you want to log runs to files. */
    private static final File TEST_RESULT_LOG_DIR = new File("/Users/trautmane/Desktop/reiser/logs");

    /** Number of times to repeat scan correction test run (to explore differences with random ordering). */
    private static final int MAX_NUMBER_OF_RUNS = 5;

    @SuppressWarnings("LocalCanBeFinal")
    private static List<StepParameters> buildStepParameters() {
        final List<StepParameters> stepParametersList = new ArrayList<>();
        double stepSize = 10.0; // 5.0, 2.5, 1.25, 0.625, 0.3125, 0.15625, 0.078125, 0.0390625, 0.01953125, 0.009765625
        double renderScale = 0.25;
        for (int i = 0; i < 10; i++) {
            final Map<Integer, Double> parameterIndexToStepSize = new LinkedHashMap<>();
            parameterIndexToStepSize.put(0, stepSize);
            parameterIndexToStepSize.put(1, stepSize);
            parameterIndexToStepSize.put(2, stepSize);
            parameterIndexToStepSize.put(3, stepSize * 10.0);

            final StepParameters stepParameters = new StepParameters(i + 1,
                                                                     parameterIndexToStepSize,
                                                                     renderScale);

            LOG.info("buildStepParameters: created {}", stepParameters);

            stepParametersList.add(stepParameters);

            stepSize = stepSize / 2.0;
            // changing renderScale did not improve results for first problem area (and significantly slowed process)
//            if (stepSize == 0.625) {
//                renderScale = 0.5;
//            }
        }
        return stepParametersList;
    }

    public static void visualizeOcellarScanCorrection()
            throws IOException {

        // TODO: Preibisch - change this to your Fiji plugins directory so that stitching plugin is available
        System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
        new ImageJ();

        final String[] scanParameterDataStrings = {
                "19.4 64.8 24.4 972.0 0",
                "32.6031   67.1633   35.4156  962.6250 0"
        };
        //{34.5953125, 175.7765625, 39.10703125, 1089.96875}; // best individual (doesn't help)

        for (final OrderedCanvasIdPair problemTilePair : getTilePairsForOcellarProblemAreas()) {
            for (final String scanParameterDataString : scanParameterDataStrings) {
                final CanvasId p = problemTilePair.getP();
                final CanvasId q = problemTilePair.getQ();
                final String qInPLayerTileId = p.getId().replace("0-0-0", "0-0-1");
                final String pInQLayerTileId = q.getId().replace("0-0-1", "0-0-0");
                final CanvasId qInPLayer = new CanvasId("", qInPLayerTileId, MontageRelativePosition.RIGHT);
                final CanvasId pInQLayer = new CanvasId("", pInQLayerTileId, MontageRelativePosition.LEFT);

                stitchFuseAndShowLayers(p, qInPLayer,
                                        pInQLayer, q,
                                        scanParameterDataString);
            }
        }

        LOG.info("visualizeOcellarScanCorrection: kill main thread when done viewing ...");

        SimpleMultiThreading.threadHaltUnClean();
    }

    public static void debugOcellarScanCorrection(final boolean visualizeResult)
            throws IOException {
        // hide all logging except from this test class
        LogbackTestTools.setRootLogLevelToError();
        LogbackTestTools.setLogLevelToInfo(LOG.getName()); // another option: setLogLevelToInfoToDebug

        if ((TEST_RESULT_LOG_DIR != null) && TEST_RESULT_LOG_DIR.exists()) {
            LogbackTestTools.setRootFileAppenderWithTimestamp(TEST_RESULT_LOG_DIR, "scan_parameters");
        }

        if (visualizeResult) {
            // TODO: Preibisch - change this to your Fiji plugins directory so that stitching plugin is available
            System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
            new ImageJ();
        }

        final int checkPeaks = 50;
        final boolean subpixelAccuracy = false;
        final int fullScaleClipPixels = 1200;
        final List<StepParameters> stepParametersList = buildStepParameters();
        final double[] originalParameters = {19.4, 64.8, 24.4, 972.0};
                                            //{34.5953125, 175.7765625, 39.10703125, 1089.96875}; // best individual (doesn't help)

        final int numberOfPairsToVisualize = 0; // change to 1 to see original pair (or more to see test pairs)

        for (final OrderedCanvasIdPair problemTilePair : getTilePairsForOcellarProblemAreas()) {

            final CanvasId p = problemTilePair.getP();
            final CanvasId q = problemTilePair.getQ();

            final TestResultWithContext bestResult = findBestScanCorrectionParameters(p,
                                                                                      q,
                                                                                      checkPeaks,
                                                                                      subpixelAccuracy,
                                                                                      numberOfPairsToVisualize,
                                                                                      fullScaleClipPixels,
                                                                                      stepParametersList,
                                                                                      originalParameters,
                                                                                      MAX_NUMBER_OF_RUNS);

            if (visualizeResult) {
                final String qInPLayerTileId = p.getId().replace("0-0-0", "0-0-1");
                final String pInQLayerTileId = q.getId().replace("0-0-1", "0-0-0");
                final CanvasId qInPLayer = new CanvasId("", qInPLayerTileId, MontageRelativePosition.RIGHT);
                final CanvasId pInQLayer = new CanvasId("", pInQLayerTileId, MontageRelativePosition.LEFT);

                stitchFuseAndShowLayers(p, qInPLayer,
                                        pInQLayer, q,
                                        buildTransformDataString(originalParameters));

                stitchFuseAndShowLayers(p, qInPLayer,
                                        pInQLayer, q,
                                        bestResult.dataString);

            }
        }

        if (visualizeResult) {
            LOG.info("debugOcellarScanCorrection: processing is done, kill main thread when done viewing ...");
            SimpleMultiThreading.threadHaltUnClean();
        }
    }

    public static TestResultWithContext findBestScanCorrectionParameters(final CanvasId p,
                                                                         final CanvasId q,
                                                                         final int checkPeaks,
                                                                         final boolean subpixelAccuracy,
                                                                         final int numberOfPairsToVisualize,
                                                                         final int fullScaleClipPixels,
                                                                         final List<StepParameters> stepParametersList,
                                                                         final double[] startingParameters,
                                                                         final int maxNumberOfRuns)
            throws IOException {

        final int maxTestsToRun = 1000;
        final double firstRenderScale = stepParametersList.get(0).renderScale;
        final RenderTileWithTransformsClient client = getOcellarClient(fullScaleClipPixels, firstRenderScale);
        double[] originalParameters = startingParameters.clone();

        final Timer timer = new Timer();
        timer.start();

        int testCountForAllRuns = 0;
        TestResultWithContext bestResult = null;
        TestResultWithContext previousBestResult = null;
        Tester tester;

        for (int i = 0; i < maxNumberOfRuns; i++) {
            LOG.info("\n\n");
            LOG.info("findBestScanCorrectionParameters: begin run {} of {}\n", (i+1), maxNumberOfRuns);

            tester = new Tester(client,
                                p,
                                q,
                                checkPeaks,
                                subpixelAccuracy,
                                numberOfPairsToVisualize,
                                originalParameters,
                                stepParametersList,
                                maxTestsToRun);

            bestResult = tester.optimizeTransformParametersForAllSteps();
            originalParameters = bestResult.transformValues.clone();
            testCountForAllRuns += tester.totalTestCount;

            if ((previousBestResult != null) && previousBestResult.dataString.equals(bestResult.dataString)) {
                LOG.info("best result for run {} was identical to previous run, ending tests", i+1);
                break;
            } else {
                previousBestResult = bestResult;
            }
        }

        LOG.info("findBestScanCorrectionParameters: done, ran {} tests in {} seconds, best result is {}",
                 testCountForAllRuns,
                 (int) (timer.stop() / 1000),
                 bestResult);

        return bestResult;
    }

    public static void stitchFuseAndShowLayers(final CanvasId firstLayerP,
                                               final CanvasId firstLayerQ,
                                               final CanvasId secondLayerP,
                                               final CanvasId secondLayerQ,
                                               final String scanCorrectionTransformDataString)
            throws IOException {

        final int fullScaleClipPixels = 3000; // increase clip size to help with visualization
        final double renderScale = 1.0;
        final RenderTileWithTransformsClient client = getOcellarClient(fullScaleClipPixels, renderScale);
        final LeafTransformSpec transformSpec = new LeafTransformSpec(SEMDistortionTransformA.class.getName(),
                                                                      scanCorrectionTransformDataString);
        final List<TransformSpec> tileTransformSpecList = Collections.singletonList(transformSpec);

        final ImagePlus firstLayerFused = stitchAndFusePair(firstLayerP,
                                                            firstLayerQ,
                                                            tileTransformSpecList,
                                                            scanCorrectionTransformDataString,
                                                            renderScale,
                                                            client);
        // firstLayerFused.show();

        final ImagePlus secondLayerFused = stitchAndFusePair(secondLayerP,
                                                             secondLayerQ,
                                                             tileTransformSpecList,
                                                             scanCorrectionTransformDataString,
                                                             renderScale,
                                                             client);
        // secondLayerFused.show();

        final ImagePlus fusedStack = ImagesToStack.run(new ImagePlus[] {firstLayerFused, secondLayerFused});
        fusedStack.setTitle("fusedStack " + scanCorrectionTransformDataString);

        fusedStack.show();

        IJ.run("Linear Stack Alignment with SIFT");

        final ImagePlus alignedStack = WindowManager.getCurrentImage();
        alignedStack.setTitle("alignedStack " + scanCorrectionTransformDataString);
    }

    public static ImagePlus stitchAndFusePair(final CanvasId p,
                                              final CanvasId q,
                                              final List<TransformSpec> tileTransformSpecList,
                                              final String scanCorrectionTransformDataString,
                                              final double renderScale,
                                              final RenderTileWithTransformsClient client)
            throws IOException {

        LOG.info("stitchAndFusePair: entry, dataString={}", scanCorrectionTransformDataString);

        final TileSpec pTileSpec = client.fetchTileSpec(p.getId());
        final ImagePlus pTilePlus = renderTile(pTileSpec,
                                               tileTransformSpecList,
                                               scanCorrectionTransformDataString,
                                               p,
                                               renderScale,
                                               client);

        LOG.info("stitchAndFusePair: rendered scan corrected tile for {}", p);

        final TileSpec qTileSpec = client.fetchTileSpec(q.getId());
        final ImagePlus qTilePlus = renderTile(qTileSpec,
                                               tileTransformSpecList,
                                               scanCorrectionTransformDataString,
                                               q,
                                               renderScale,
                                               client);

        LOG.info("stitchAndFusePair: rendered scan corrected tile for {}, stitching tiles ...", q);

        final StitchingParameters stitchingParameters =
                buildStitchingParameters(Stitching_Pairwise.defaultCheckPeaks,
                                         Stitching_Pairwise.defaultSubpixelAccuracy);

        final PairWiseStitchingResult result = PairWiseStitchingImgLib.stitchPairwise(pTilePlus,
                                                                                      qTilePlus,
                                                                                      null,
                                                                                      null,
                                                                                      1,
                                                                                      1,
                                                                                      stitchingParameters);

        LOG.info("stitchAndFusePair: fusing tiles ...");

        final ArrayList<ImagePlus> images = new ArrayList<>();
        images.add(pTilePlus);
        images.add(qTilePlus);

        final ArrayList<InvertibleBoundable> models = new ArrayList<>();
        final TranslationModel2D model1 = new TranslationModel2D();
        final TranslationModel2D model2 = new TranslationModel2D();
        model2.set( result.getOffset( 0 ), result.getOffset( 1 ) );
        models.add( model1 );
        models.add( model2 );

        final ImagePlus fused = Fusion.fuse(new UnsignedByteType(),
                                            images,
                                            models,
                                            2,
                                            Stitching_Pairwise.defaultSubpixelAccuracy,
                                            Stitching_Pairwise.defaultFusionMethod,
                                            null,
                                            false,
                                            true,
                                            false);
        fused.setTitle(pTileSpec.getZ() + " fused " + scanCorrectionTransformDataString);

        LOG.info("stitchAndFusePair: exit");

        return fused;
    }

    private static class TestResultWithContext {

        public final String dataString;
        public final double[] transformValues;
        public final PairWiseStitchingResult result;
        public final double renderScale;

        public TestResultWithContext(final String dataString,
                                     final double[] transformValues,
                                     final PairWiseStitchingResult result,
                                     final double renderScale) {
            this.dataString = dataString;
            this.transformValues = transformValues;
            this.result = result;
            this.renderScale = renderScale;
        }

        @Override
        public String toString() {
            return "parameters " + dataString + " with " + resultToString(result) + ", renderScale " + renderScale;
        }

        @SuppressWarnings("SameParameterValue")
        private boolean isBetter(final TestResultWithContext that,
                                 final double correlationThreshold,
                                 final float[] originalOffset,
                                 final float offsetThreshold) {
            // TODO: find 'limitMaxOverlap' parameter that Preibisch mentioned (or confirm offset check is sufficient)
            boolean better = false;
            if (this.result != null) {
                final double correlationDelta = this.result.getCrossCorrelation() - that.result.getCrossCorrelation();
                if (correlationDelta > correlationThreshold) {
                    final float[] offset = this.result.getOffset();
                    final float deltaX = Math.abs(originalOffset[0] - offset[0]);
                    final float deltaY = Math.abs(originalOffset[1] - offset[1]);
                    if ((deltaX > offsetThreshold) || (deltaY > offsetThreshold)) {
                        LOG.warn("isBetter: ignoring better correlation result {} " +
                                 "because offset changed too much from original offset {}",
                                 this, Arrays.toString(originalOffset));
                    } else {
                        better = true;
                    }
                }
            }
            return better;
        }
    }

    private static class StepParameters {
        private final int stepNumber;
        private final Map<Integer, Double> parameterIndexToStepSize;
        private final double renderScale;

        public StepParameters(final int stepNumber,
                              final Map<Integer, Double> parameterIndexToStepSize,
                              final double renderScale) {
            this.stepNumber = stepNumber;
            this.parameterIndexToStepSize = parameterIndexToStepSize;
            this.renderScale = renderScale;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(1024);
            sb.append("{stepNumber: ").append(String.format("%2d", stepNumber)).append(", parameterIndexToStepSize:{");
            for (final Integer index : parameterIndexToStepSize.keySet()) {
                final double stepSize = parameterIndexToStepSize.get(index);
                sb.append(String.format("%2d", index)).append("=").append(String.format("% 16.10f", stepSize));
            }
            sb.append("}, renderScale: ").append(String.format("% 4.2f", renderScale)).append("}");
            return sb.toString();
        }

        public List<Integer> getParameterIndexesToTest() {
            return new ArrayList<>(parameterIndexToStepSize.keySet());
        }

        public double getStepSize(final int forParameterIndex) {
            return parameterIndexToStepSize.get(forParameterIndex);
        }
    }

    private static class Tester {
        private final RenderTileWithTransformsClient client;
        private final List<StepParameters> stepParametersList;
        private final CanvasId pCanvasId;
        private final TileSpec pTileSpec;
        private final CanvasId qCanvasId;
        private final TileSpec qTileSpec;
        private final StitchingParameters stitchingParameters;
        private final int numberOfPairsToVisualize;
        private final double[] originalParameters;
        private final int maxNumberOfTests;

        private TestResultWithContext originalResult;
        private TestResultWithContext bestResult;
        private final Set<String> testedDataStrings;

        /**
         * Flag indicating whether an improved parameter has been found in the current step
         * for each of the (4) transform coefficient parameters
         */
        private int totalTestCount;

        public Tester(final RenderTileWithTransformsClient client,
                      final CanvasId pCanvasId,
                      final CanvasId qCanvasId,
                      final int checkPeaks,
                      final boolean subpixelAccuracy,
                      final int numberOfPairsToVisualize,
                      final double[] originalParameters,
                      final List<StepParameters> stepParametersList,
                      final int maxNumberOfTests)
                throws IOException {

            this.client = client;
            this.pCanvasId = pCanvasId;
            this.pTileSpec = client.fetchTileSpec(pCanvasId.getId());
            this.qCanvasId = qCanvasId;
            this.qTileSpec = client.fetchTileSpec(qCanvasId.getId());
            this.stitchingParameters = buildStitchingParameters(checkPeaks, subpixelAccuracy);
            this.numberOfPairsToVisualize = numberOfPairsToVisualize;
            this.originalParameters = originalParameters;
            this.stepParametersList = stepParametersList;
            this.maxNumberOfTests = maxNumberOfTests;
            this.testedDataStrings = new HashSet<>();
        }

        public TestResultWithContext optimizeTransformParametersForAllSteps() {

            final StepParameters firstStepParameters = stepParametersList.get(0);
            final String originalTransformDataString = buildTransformDataString(originalParameters);
            final String originalDataStringWithRenderScale = dataStringWithRenderScale(originalTransformDataString,
                                                                                       firstStepParameters.renderScale);

            this.testedDataStrings.clear();
            this.testedDataStrings.add(originalDataStringWithRenderScale);

            final PairWiseStitchingResult originalStitchResult = deriveStitchingResult(originalTransformDataString,
                                                                                       firstStepParameters.renderScale,
                                                                                       0);
            this.originalResult = new TestResultWithContext(originalTransformDataString,
                                                            originalParameters,
                                                            originalStitchResult,
                                                            firstStepParameters.renderScale);
            this.bestResult = this.originalResult;

            final int maxFailedSteps = 3;
            this.totalTestCount = 0;

            int stepCount = 0;
            int failedStepCount = 0;
            for (final StepParameters stepParameters : stepParametersList) {

                LOG.info("---------------------------------");
                LOG.info("optimizeTransformParametersForAllSteps: begin step {} with {}",
                         stepParameters.stepNumber,
                         bestResult);
                LOG.info("---------------------------------");

                // randomly order parameter optimization for each step
                final List<Integer> transformParameterIndexes = stepParameters.getParameterIndexesToTest();
                Collections.shuffle(transformParameterIndexes);

                boolean foundSomethingBetterForStepSize = false;
                boolean foundSomethingBetterForPass;
                do {

                    foundSomethingBetterForPass = false;
                    for (final int indexOfTransformParameterToChange : transformParameterIndexes) {
                        final boolean foundSomethingBetterForParameter =
                                optimizeTransformParameterForStep(indexOfTransformParameterToChange,
                                                                  stepParameters);
                        foundSomethingBetterForPass = foundSomethingBetterForPass || foundSomethingBetterForParameter;
                    }

                    foundSomethingBetterForStepSize = foundSomethingBetterForStepSize || foundSomethingBetterForPass;

                } while (foundSomethingBetterForPass);

                if (! foundSomethingBetterForStepSize) {
                    failedStepCount++;
                    if (failedStepCount >= maxFailedSteps) {
                        LOG.info("optimizeTransformParametersForAllSteps: stopping tests since nothing improved for last {} steps",
                                 maxFailedSteps);
                        break;
                    }
                }

                if (totalTestCount >= maxNumberOfTests) {
                    break;
                }

                final int nextStepIndex = stepCount + 1;
                if (nextStepIndex < stepParametersList.size()) {
                    final double nextStepRenderScale = stepParametersList.get(nextStepIndex).renderScale;
                    if (stepParameters.renderScale != nextStepRenderScale) {
                        scaleResultsForNextStep(nextStepRenderScale);
                    }
                }
                stepCount++;
            }

            LOG.info("optimizeTransformParametersForAllSteps: after {} tests, best result is {}",
                     totalTestCount,
                     bestResult);

            return bestResult;
        }

        private void scaleResultsForNextStep(final double nextStepRenderScale) {

            LOG.info("scaleResultsForNextStep: entry, nextStepRenderScale is {}", nextStepRenderScale);

            PairWiseStitchingResult rescaledResult = deriveStitchingResult(bestResult.dataString,
                                                                           nextStepRenderScale,
                                                                           0);
            bestResult = new TestResultWithContext(bestResult.dataString,
                                                   bestResult.transformValues,
                                                   rescaledResult,
                                                   nextStepRenderScale);
            testedDataStrings.add(dataStringWithRenderScale(bestResult.dataString,
                                                            nextStepRenderScale));

            LOG.info("scaleResultsForNextStep: rescaled                 best result {} with renderScale {}",
                     bestResult,
                     nextStepRenderScale);

            rescaledResult = deriveStitchingResult(originalResult.dataString,
                                                   nextStepRenderScale,
                                                   0);
            originalResult = new TestResultWithContext(originalResult.dataString,
                                                       originalResult.transformValues,
                                                       rescaledResult,
                                                       nextStepRenderScale);
            testedDataStrings.add(dataStringWithRenderScale(originalResult.dataString,
                                                            nextStepRenderScale));

            LOG.info("scaleResultsForNextStep: rescaled             original result {} with renderScale {}",
                     originalResult,
                     nextStepRenderScale);
        }

        private boolean optimizeTransformParameterForStep(final int forParameterIndex,
                                                          final StepParameters stepParameters) {
            boolean foundSomethingBetter = false;

            if (totalTestCount < maxNumberOfTests) {

                // clone best result as base before running tests
                final double[] baseValues = bestResult.transformValues.clone();
                final double stepSize = stepParameters.getStepSize(forParameterIndex);

                final TestResultWithContext upResult = runOneTest(baseValues,
                                                                  forParameterIndex,
                                                                  stepSize,
                                                                  stepParameters.renderScale);
                if (upResult.isBetter(bestResult,
                                      CORRELATION_THRESHOLD,
                                      originalResult.result.getOffset(),
                                      OFFSET_THRESHOLD)) {
                    bestResult = upResult;
                    foundSomethingBetter = true;
                }

                final TestResultWithContext downResult = runOneTest(baseValues,
                                                                    forParameterIndex,
                                                                    -stepSize,
                                                                    stepParameters.renderScale);
                if (downResult.isBetter(bestResult,
                                        CORRELATION_THRESHOLD,
                                        originalResult.result.getOffset(),
                                        OFFSET_THRESHOLD)) {
                    bestResult = downResult;
                    foundSomethingBetter = true;
                }

            }

            final String betterMsg = foundSomethingBetter ? "found best" : " kept best";
            LOG.info("optimizeStep: for arg {} step {},         {}            {}, totalTestCount is {}",
                     forParameterIndex,
                     stepParameters.stepNumber,
                     betterMsg,
                     bestResult,
                     totalTestCount);

            return foundSomethingBetter;
        }

        private TestResultWithContext runOneTest(final double[] baseValues,
                                                 final int indexOfTransformParameterToChange,
                                                 final double stepSize,
                                                 final double renderScale) {
            totalTestCount++;

            final double[] testValues = baseValues.clone();
            testValues[indexOfTransformParameterToChange] = testValues[indexOfTransformParameterToChange] + stepSize;
            final String testDataString = buildTransformDataString(testValues);
            final String dataStringWithRenderScale = dataStringWithRenderScale(testDataString, renderScale);

            final PairWiseStitchingResult testResult;
            if (testedDataStrings.contains(dataStringWithRenderScale)) {
                LOG.info("runOneTest: already tested {}", testDataString);
                testResult = null;
            } else {
                testResult = deriveStitchingResult(testDataString, renderScale, testedDataStrings.size());
                testedDataStrings.add(dataStringWithRenderScale);
            }

            final TestResultWithContext testResultWithContext = new TestResultWithContext(testDataString,
                                                                                          testValues,
                                                                                          testResult,
                                                                                          renderScale);
            if (testResult != null) {
                LOG.info("runOneTest:   for arg {} stepSize {},  returning {}",
                         indexOfTransformParameterToChange,
                         String.format("% 16.10f", stepSize),
                         testResultWithContext);
            }

            return testResultWithContext;
        }

        private PairWiseStitchingResult deriveStitchingResult(final String transformDataString,
                                                              final double renderScale,
                                                              final int numberOfPairsTested) {

            final LeafTransformSpec transformSpec = new LeafTransformSpec(SEMDistortionTransformA.class.getName(),
                                                                          transformDataString);
            final List<TransformSpec> tileTransformSpecList = Collections.singletonList(transformSpec);

            final ImagePlus pTilePlus = renderTile(pTileSpec, tileTransformSpecList, transformDataString,
                                                   pCanvasId, renderScale, client);
            final ImagePlus qTilePlus = renderTile(qTileSpec, tileTransformSpecList, transformDataString,
                                                   qCanvasId, renderScale, client);

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


    }

    public static String buildTransformDataString(final double[] transformationParameters) {
        final StringBuilder dataStringBuilder = new StringBuilder();
        for (final double p : transformationParameters) {
            dataStringBuilder.append(String.format("%9.4f", p)).append(" ");
        }
        dataStringBuilder.append("0"); // last 0 = x dimension
        return dataStringBuilder.toString();
    }

    public static String dataStringWithRenderScale(final String dataString,
                                                   final double renderScale) {
        return dataString + " renderScale:" + renderScale;
    }

    public static ImagePlus renderTile(final TileSpec tileSpec,
                                       final List<TransformSpec> tileTransforms,
                                       final String dataString,
                                       final CanvasId canvasId,
                                       final double renderScale,
                                       final RenderTileWithTransformsClient client) {
        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                client.renderTile(tileSpec, tileTransforms, renderScale, canvasId, null);
        final ImageProcessor croppedTile = quickCropMaskedArea(ipwm);
        return new ImagePlus(tileSpec.getTileId() + " " + dataString, croppedTile);
    }

    public static StitchingParameters buildStitchingParameters(final int checkPeaks,
                                                               final boolean subpixelAccuracy) {
        final StitchingParameters stitchingParameters = new StitchingParameters();

        // static parameters
        stitchingParameters.dimensionality = 2;
        stitchingParameters.fusionMethod = 0;
        stitchingParameters.fusedName = "";
        stitchingParameters.addTilesAsRois = false;
        stitchingParameters.computeOverlap = true;
        stitchingParameters.ignoreZeroValuesFusion = false;
        stitchingParameters.downSample = false;
        stitchingParameters.displayFusion = false;
        stitchingParameters.invertX = false;
        stitchingParameters.invertY = false;
        stitchingParameters.ignoreZStage = false;
        stitchingParameters.xOffset = 0.0;
        stitchingParameters.yOffset = 0.0;
        stitchingParameters.zOffset = 0.0;

        // dynamic parameters
        stitchingParameters.checkPeaks = checkPeaks;
        stitchingParameters.subpixelAccuracy = subpixelAccuracy;

        return stitchingParameters;
    }

    public static String resultToString(final PairWiseStitchingResult result) {
        return result == null ? "null" :
               String.format("crossCorrelation %9.7f, phaseCorrelation %7.5f, offset %s",
                             result.getCrossCorrelation(),
                             result.getPhaseCorrelation(),
                             Arrays.toString(result.getOffset()));
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

    public static RenderTileWithTransformsClient getOcellarClient(final Integer fullScaleClipPixels,
                                                                  final double renderScale)
            throws IOException {
        final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();
        parameters.renderWeb = new RenderWebServiceParameters();
        parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.renderWeb.owner = "reiser";
        parameters.renderWeb.project = "Z0422_05_Ocellar";
        parameters.stack = "v3_acquire";

        if (fullScaleClipPixels != null) {
            parameters.featureRenderClip.clipWidth = fullScaleClipPixels;
            parameters.featureRenderClip.clipHeight = fullScaleClipPixels;
        }
        parameters.scale = renderScale;

        return new RenderTileWithTransformsClient(parameters);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTileWithTransformsClientTest.class);
}
