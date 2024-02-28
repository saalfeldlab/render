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

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Streak corrector with a configurable/parameterized mask that can also be used as {@link Filter}.
 * This applies the mask of the {@link SmoothMaskStreakCorrector} to parts of the input image.
 *
 * @author Michael Innerberger
 */
public class LocalSmoothMaskStreakCorrector extends SmoothMaskStreakCorrector {

	private int gaussianBlurRadius;
	private float initialThreshold;
	private float finalThreshold;


	// TODO: this duplicates LocalConfigurableMaskStreakCorrector; find a way to unify this
    public LocalSmoothMaskStreakCorrector(
			final SmoothMaskStreakCorrector corrector,
			final int gaussianBlurRadius,
			final float initialThreshold,
			final float finalThreshold) {
        super(corrector);
		this.gaussianBlurRadius = gaussianBlurRadius;
		this.initialThreshold = initialThreshold;
		this.finalThreshold = finalThreshold;
	}

	@Override
	public void init(final Map<String, String> params) {
		final List<String> values = new LinkedList<>(List.of(Filter.getCommaSeparatedStringParameter(DATA_STRING_NAME, params)));

		final int nParams = values.size();
		final boolean canHoldAllParameters = (nParams >= 3);
		if (! canHoldAllParameters) {
			throw new IllegalArgumentException(DATA_STRING_NAME +
													   " must have pattern <corrector arguments>,<gaussianBlurRadius>,<initialThreshold>,<finalThreshold>");
		}

		this.finalThreshold = Float.parseFloat(values.remove(nParams - 1));
		this.initialThreshold = Float.parseFloat(values.remove(nParams - 2));
		this.gaussianBlurRadius = Integer.parseInt(values.remove(nParams - 3));

		final String remainingParams = String.join(",", values);
		super.init(Map.of(DATA_STRING_NAME, remainingParams));
	}

	@Override
	public String toDataString() {
		return super.toDataString() + ',' +
				gaussianBlurRadius + ',' +
				initialThreshold + ',' +
				finalThreshold;
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

		// subtract fixed from original to get streaks, which is where the correction should be applied
		final RandomAccessibleInterval<FloatType> weight =
				Converters.convertRAI(original,
									  fixed,
									  (i1,i2,o) -> o.set(Math.abs(i1.get() - i2.get())),
									  new FloatType());

		weigthedSum(ip, originalIP.getProcessor(), weight);
	}

	private void weigthedSum(final ImageProcessor target,
									final ImageProcessor original,
									final RandomAccessibleInterval<FloatType> weight) {

		final ImagePlus weigthIP = ImageJFunctions.wrapFloat(weight, "weight");
		final GaussianBlur gaussianBlur = new GaussianBlur();
		final ImagePlus extendedWeight = extendBorder(weigthIP, gaussianBlurRadius);

		// figure out where exactly the streaks are
		threshold(extendedWeight.getProcessor(), initialThreshold);

		// create neighborhood of the streaks
		gaussianBlur.blurGaussian(extendedWeight.getProcessor(), gaussianBlurRadius);
		threshold(extendedWeight.getProcessor(), finalThreshold);

		// smooth the neighborhood
		gaussianBlur.blurGaussian(extendedWeight.getProcessor(), gaussianBlurRadius);
		final ImagePlus smoothedWeight = crop(extendedWeight, gaussianBlurRadius);
		final ImageProcessor w = smoothedWeight.getProcessor();

		// normalize the weight
		w.resetMinAndMax();

		for (int i = 0; i < w.getPixelCount(); i++) {
			final int value = (int) (target.get(i) * w.getf(i) + original.get(i) * (1 - w.getf(i)));
			target.set(i, value);
		}
	}

	private static void threshold(final ImageProcessor ip, final float threshold) {
		for (int i = 0; i < ip.getPixelCount(); i++) {
			final int value = ip.getf(i) > threshold ? 1 : 0;
			ip.setf(i, value);
		}
	}

	// TODO: this is copied from BackgroundCorrectionFilter and should be refactored
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
}
