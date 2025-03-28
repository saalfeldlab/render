package org.janelia.render.client.multisem;

import ij.ImagePlus;
import ij.gui.Roi;

import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;

/**
 * Tests the {@link MultiSemZLayerOffsetClient} class.
 */
public class MultiSemZLayerOffsetClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MultiSemZLayerOffsetClient.Parameters());
    }

    public static void main(final String[] args) {

//        printTranslationsForOneSfov();
//        System.exit(0);

        // set log level to WARN for alignment package to reduce log output
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger("org.janelia.alignment").setLevel(Level.WARN);

        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[] {
                "--baseDataUrl", "http://10.40.3.113:8080/render-ws/v1",
                "--owner", "hess_wafers_60_61",
                "--project", "w60_serial_360_to_369",
                "--stack", "w60_s360_r00_d20_gc" //,
                //"--offsetStackSuffix", "_offset"
        };

        MultiSemZLayerOffsetClient.main(effectiveArgs);
    }

    public static String buildImageUrl(final int scan ) {
        return String.format("https://storage.googleapis.com/janelia-spark-test/hess_wafer_60_data/scan_%03d/slab_0160/mfov_0006/sfov_001.png",
                             scan);
    }

    public static void printTranslationsForOneSfov() {

        final int firstScan = 4;
        final int lastScan = 76;

        final ImagePlus firstImagePlus = new ImagePlus(buildImageUrl(firstScan));
        final Roi roi = new Roi(0, 0, firstImagePlus.getWidth(), firstImagePlus.getHeight());
        final StitchingParameters stitchingParameters = MultiSemZLayerOffsetClient.createStitchingParameters("test");
        final double renderScale = 1.0;

        int previousScan = -1;
        ImagePlus previousImagePlus = firstImagePlus;
        int previousFullScaleOffsetX = 0;
        int previousFullScaleOffsetY = 0;

        for (int scan = firstScan; scan <= lastScan; scan++) {

            if (scan == 7 || scan == 19) {
                continue;
            }

            final ImagePlus imagePlus = new ImagePlus(buildImageUrl(scan));
            final PairWiseStitchingResult result =
                    PairWiseStitchingImgLib.stitchPairwise(previousImagePlus,
                                                           imagePlus,
                                                           roi, roi, 0, 0,
                                                           stitchingParameters);

            final int fullScaleOffsetFromPreviousX = (int) (result.getOffset(0) / renderScale);
            final int fullScaleOffsetFromPreviousY = (int) (result.getOffset(1) / renderScale);

            final int fullScaleOffsetX = fullScaleOffsetFromPreviousX + previousFullScaleOffsetX;
            final int fullScaleOffsetY = fullScaleOffsetFromPreviousY + previousFullScaleOffsetY;

            final String message =
                    String.format("translation between scan %2d and %2d is %5d, %5d and between scan %2d and %2d is %5d, %5d",
                                  scan, previousScan,
                                  fullScaleOffsetFromPreviousX, fullScaleOffsetFromPreviousY,
                                  scan, firstScan,
                                  fullScaleOffsetX, fullScaleOffsetY);
            System.out.println(message);

            previousScan = scan;
            previousImagePlus = imagePlus;
            previousFullScaleOffsetX = fullScaleOffsetX;
            previousFullScaleOffsetY = fullScaleOffsetY;
        }

    }

}
