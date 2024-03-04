package org.janelia.alignment.destreak;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Extension of {@link ConfigurableMaskStreakCorrector} filter that produces an image containing
 * extracted streaks instead of a de-streaked image.  This implementation was created to support
 * analysis of Christopher Bleck's tough resin experiments.
 * The streak extraction process is very slow and involves multiple image copies and conversions,
 * therefore this filter should primarily be used for distributed client-side processing
 * (as opposed to using it for dynamic server-side processing).
 */
public class ConfigurableMaskStreakExtractor
        extends ConfigurableMaskStreakCorrector {

    public ConfigurableMaskStreakExtractor() {
    }

    public ConfigurableMaskStreakExtractor(final ConfigurableMaskStreakCorrector corrector) {
        super(corrector);
    }

    @Override
    public void process(final ImageProcessor ip,
                        final double scale) {

        // save original image for later subtraction
        final ImagePlus originalIP = new ImagePlus("original", ip.convertToFloat());
        final Img<FloatType> original = ImageJFunctions.wrapFloat(originalIP);
        checkWrappingSucceeded(original, ip, FloatType.class);

		// de-streak image
        super.process(ip, scale);

        final ImagePlus fixedIP = new ImagePlus("fixed", ip.convertToFloat());
        final Img<FloatType> fixed = ImageJFunctions.wrapFloat(fixedIP);
        checkWrappingSucceeded(fixed, ip, FloatType.class);

		// subtract fixed from original to get streaks - has to be FloatType since there may be negative values
        final RandomAccessibleInterval<FloatType> streaks =
                Converters.convertRAI(original,
                                      fixed,
                                      (i1,i2,o) -> o.set(i1.get() - i2.get()),
                                      new FloatType());

        convertStreaksToByteProcessor(streaks, ip);
    }

    private static void convertStreaksToByteProcessor(final RandomAccessibleInterval<FloatType> streaks,
                                                      final ImageProcessor ip) {

        final ImagePlus streaksImagePlus = ImageJFunctions.wrapFloat(streaks, "streaks");
        final ImageProcessor streaksFloatImageProcessor = streaksImagePlus.getProcessor();

        // reset min and max before converting to byte processor so that streaks are more easily viewed
        streaksFloatImageProcessor.resetMinAndMax();

        final ImageProcessor streaksImageProcessor = streaksFloatImageProcessor.convertToByteProcessor();
        for (int i = 0; i < streaksImageProcessor.getPixelCount(); i++) {
            ip.set(i, streaksImageProcessor.get(i));
        }
    }
}
