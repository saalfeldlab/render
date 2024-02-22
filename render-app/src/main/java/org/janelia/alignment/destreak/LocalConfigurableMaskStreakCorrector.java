package org.janelia.alignment.destreak;

import ij.ImagePlus;
import ij.plugin.filter.GaussianBlur;
import ij.process.ImageProcessor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.alignment.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streak corrector with a configurable/parameterized mask that can also be used as {@link Filter}.
 * This applies the mask of the {@link ConfigurableMaskStreakExtractor} to parts of the input image.
 *
 * @author Michael Innerberger
 */
public class LocalConfigurableMaskStreakCorrector
        extends ConfigurableMaskStreakCorrector
        implements Filter {

	private static int DOWN_SAMPLE_FACTOR = 2;
	private static final int GAUSSIAN_BLUR_RADIUS = 100;
	private static final float INITIAL_THRESHOLD = 20.0f;
	private static final float FINAL_THRESHOLD = 0.1f;


    public LocalConfigurableMaskStreakCorrector() {
        this(1);
    }

    public LocalConfigurableMaskStreakCorrector(final int numThreads) {
        super(numThreads);
    }

    public LocalConfigurableMaskStreakCorrector(final int numThreads,
												final int fftWidth,
												final int fftHeight,
												final int extraX,
												final int extraY,
												final int[][] regionsToClear) {
        super(numThreads, fftWidth, fftHeight, extraX, extraY, regionsToClear);
    }

    public LocalConfigurableMaskStreakCorrector(final ConfigurableMaskStreakCorrector corrector) {
        super(corrector);
    }

    @Override
    public void process(final ImageProcessor ip, final double scale) {
		// save original image for later subtraction
		final ImagePlus originalIP = new ImagePlus("original", ip.duplicate());
		final Img<UnsignedByteType> original = ImageJFunctions.wrapByte(originalIP);

		// de-streak image
		super.process(ip, scale);

		final ImagePlus fixedIP = new ImagePlus("fixed", ip);
		final Img<UnsignedByteType> fixed = ImageJFunctions.wrapByte(fixedIP);


		// resize to speed up processing
//		final int targetWidth = DOWN_SAMPLE_FACTOR * ip.getWidth();
//		final int targetHeight = DOWN_SAMPLE_FACTOR * ip.getHeight();
//		final ImagePlus maskExtended = Scaler.resize(fixedIP, targetWidth, targetHeight, 1, "bilinear");
//
//		// median filtering for actual background computation
//		final double downscaledRadius = GAUSSIAN_BLUR_RADIUS * DOWN_SAMPLE_FACTOR;
//		final ImagePlus extendedBackground = extendBorder(maskExtended, downscaledRadius);
//
//		final GaussianBlur gaussianBlur = new GaussianBlur();
//		gaussianBlur.blurGaussian(extendedBackground.getProcessor(), downscaledRadius);
//		final ImagePlus filteredBackground = crop(extendedBackground, downscaledRadius);

//		final ImagePlus fixedImagePlus = new ImagePlus("fixed", ip);
//		gaussianBlur.blurGaussian(fixedImagePlus.getProcessor(), GAUSSIAN_BLUR_RADIUS);

		// subtract fixed from original to get streaks and threshold
		// has to be FloatType since there may be negative values
		final RandomAccessibleInterval<FloatType> weight =
				Converters.convertRAI(original,
									  fixed,
									  (i1,i2,o) -> o.set(Math.abs(i1.get() - i2.get())),
									  new FloatType());

		weigthedSum(ip, originalIP.getProcessor(), weight);
	}

	private static void weigthedSum(final ImageProcessor target,
									final ImageProcessor original,
									final RandomAccessibleInterval<FloatType> weight) {

		final ImagePlus weigthIP = ImageJFunctions.wrapFloat(weight, "weight");
		final ImagePlus extendedWeight = extendBorder(weigthIP, GAUSSIAN_BLUR_RADIUS);

		final GaussianBlur gaussianBlur = new GaussianBlur();
		threshold(extendedWeight.getProcessor(), INITIAL_THRESHOLD);
//		threshold(extendedWeight.getProcessor(), 0);
		gaussianBlur.blurGaussian(extendedWeight.getProcessor(), GAUSSIAN_BLUR_RADIUS);
		threshold(extendedWeight.getProcessor(), FINAL_THRESHOLD);
		gaussianBlur.blurGaussian(extendedWeight.getProcessor(), GAUSSIAN_BLUR_RADIUS);
		final ImagePlus smoothedWeight = crop(extendedWeight, GAUSSIAN_BLUR_RADIUS);
		final ImageProcessor w = smoothedWeight.getProcessor();

//		final ImageProcessor w = weigthIP.getProcessor();
//		gaussianBlur.blurGaussian(w, GAUSSIAN_BLUR_RADIUS);

		// reset min and max before converting to byte processor so that streaks are more easily viewed
		w.resetMinAndMax();
		final float maxValue = (float) w.getMax();
		System.out.println("max value = " + maxValue);
		for (int i = 0; i < w.getPixelCount(); i++) {
			w.setf(i, w.getf(i) / maxValue);
		}

		for (int i = 0; i < w.getPixelCount(); i++) {
//			if (weightImageProcessor.get(i) > 0) {
//				System.out.println("image processor = " + weightImageProcessor.get(i) + ", float processor = " + w.getf(i));
//			}
//			final int value = (int) (target.get(i) * w.getf(i) + original.get(i) * (1 - w.getf(i)));
			final int value = (int) (255 * w.getf(i));
			target.set(i, value);
		}
	}

	private static void threshold(final ImageProcessor ip, final float threshold) {
		for (int i = 0; i < ip.getPixelCount(); i++) {
			final int value = ip.getf(i) > threshold ? 1 : 0;
			ip.setf(i, value);
		}
	}

	private static ImagePlus extendBorder(final ImagePlus input, final double padding) {
		final Img<FloatType> in = ImageJFunctions.wrap(input);
		final long extendSize = (long) Math.ceil(padding);
		final IntervalView<FloatType> view = Views.expandMirrorSingle(in, extendSize, extendSize);

		// make copy, otherwise the changes of the median filter are not visible
		// this leads to a very hard to find bug, so don't delete this copy!
		final ImagePlusImg<FloatType, FloatArray> test = ImagePlusImgs.floats(input.getWidth() + 2 * extendSize, input.getHeight() + 2 * extendSize);
		LoopBuilder.setImages(view, test).forEachPixel((v, t) -> t.set(v.get()));

		final ImagePlus out = test.getImagePlus();
		out.getProcessor().setMinAndMax(0.0, 255.0);
		return out;
	}

	private static ImagePlus crop(final ImagePlus input, final double padding) {
		final long cropSize = (long) Math.ceil(padding);
		final long[] min = new long[] {cropSize, cropSize};
		final long[] max = new long[] {input.getWidth() - cropSize - 1, input.getHeight() - cropSize - 1};
		final Img<FloatType> in = ImageJFunctions.wrap(input);

		final RandomAccessibleInterval<FloatType> roi = Views.interval(in, min, max);
		final ImagePlus out = ImageJFunctions.wrap(roi, "cropped");
		out.getProcessor().setMinAndMax(0.0, 255.0);
		return out;
	}

    private static final Logger LOG = LoggerFactory.getLogger(LocalConfigurableMaskStreakCorrector.class);
}
