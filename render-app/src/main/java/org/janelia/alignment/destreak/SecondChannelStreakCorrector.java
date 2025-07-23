package org.janelia.alignment.destreak;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.util.ImageProcessorCache;

public class SecondChannelStreakCorrector {

	private static final int MEAN_FILTER_SIZE = 100;
	private static final int INNER_CUTOFF = 75;
	private static final int BAND_WIDTH = 3;
	private static final double ANGLE = 0.0;

	private final double threshold;
	private final int blurRadius;
	private final float secondChannelBaseWeight;
	private final int numThreads;

	public static void main(final String[] args) {
		// Paths to the two channels
		final String path = "file:///Users/innerbergerm/Data/streak-correction/second-channel-experiments/jrc_zf-pancreas-1_z0039.h5";
		final String firstChannelPath = path + "?dataSet=/0-0-0/c0";
		final String secondChannelPath = path + "?dataSet=/0-0-0/c1";

		// Load and display the two channels
		final ImagePlus firstChannel = new ImagePlus("First", load(firstChannelPath));
		final ImagePlus secondChannel = new ImagePlus("Second", load(secondChannelPath));

		// Correct streaks by combining the two channels
		final SecondChannelStreakCorrector corrector = new SecondChannelStreakCorrector();
		final ImagePlus combined = corrector.process(firstChannel, secondChannel);
		new ImageJ();
		combined.setTitle("Combined");
		combined.show();
	}

	public SecondChannelStreakCorrector() {
		this(100, 5, 0.2f, 1);
	}

	public SecondChannelStreakCorrector(final double threshold,
										final int blurRadius,
										final float secondChannelBaseWeight,
										final int numThreads) {
		this.threshold = threshold;
		this.blurRadius = blurRadius;
		this.secondChannelBaseWeight = secondChannelBaseWeight;
		this.numThreads = numThreads;
	}

	public ImagePlus process(final ImagePlus firstChannel, final ImagePlus secondChannel) {
		// Shift second channel to match intensity of first channel and smooth it with median filter
		matchHistograms(firstChannel, secondChannel);
		IJ.run(secondChannel, "Gaussian Blur...", "sigma=0.7");

		// Create a streak mask
		final StreakFinder finder = new StreakFinder(MEAN_FILTER_SIZE, threshold, blurRadius);
		final ImagePlus mask = finder.createStreakMask(firstChannel);

		// Combine the two channels using the mask
		final ImageProcessor combinedProcessor = combine(
				firstChannel.getProcessor(),
				secondChannel.getProcessor(),
				mask.getProcessor(),
				secondChannelBaseWeight);

		final ImagePlus combined = new ImagePlus("Combined", combinedProcessor);
		final FftDimensions fftDims = FftDimensions.getFor(combined.getWidth(), combined.getHeight());

		final SmoothMaskStreakCorrector corrector = new SmoothMaskStreakCorrector(numThreads, fftDims.width, fftDims.height, INNER_CUTOFF, BAND_WIDTH, ANGLE);
		corrector.process16bit(combined.getProcessor(), 1.0);

		return combined;
	}

	private static ImageProcessor load(final String path) {
		final int downsampleLevel = 0;
		final boolean isMask = false;
		final boolean convertTo16Bit = true;
		final ImageLoader.LoaderType loaderType = ImageLoader.LoaderType.H5_SLICE;
		final int slice = 0;
		final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;


		return cache.get(path, downsampleLevel, isMask, convertTo16Bit, loaderType, slice);
	}

	private static void matchHistograms(final ImagePlus source, final ImagePlus target) {
		final float[] cdfSrc = computeCdf(source);
		final float[] cdfTgt = computeCdf(target);
		final int[] lut = new int[cdfSrc.length];
		int j = 0;
		for(int i = 0; i < lut.length; i++) {
			while(j < cdfSrc.length && cdfTgt[i] > cdfSrc[j]) {
				j++;
			}
			lut[i] = j;
		}
		target.getProcessor().applyTable(lut);
	}

	private static float[] computeCdf(final ImagePlus imp) {
		final ImageProcessor ip = imp.getProcessor();
		final int[] histogram = ip.getHistogram();
		final float[] cdf = new float[histogram.length];
		cdf[0] = histogram[0];
		for (int i = 1; i < histogram.length; i++) {
			cdf[i] = cdf[i - 1] + histogram[i];
		}
		final float total = cdf[cdf.length - 1];
		for (int i = 0; i < cdf.length; i++) {
			cdf[i] /= total; // Normalize to [0, 1]
		}
		return cdf;
	}

	private static ImageProcessor combine(final ImageProcessor firstChannel,
										  final ImageProcessor secondChannel,
										  final ImageProcessor mask,
										  final float baseWeight) {
		final ImageProcessor combined = firstChannel.convertToFloat().duplicate();
		final int n = firstChannel.getPixelCount();

		for (int i = 0; i < n; ++i) {
			final float m = mask.getf(i) / 255.0f;
			final float w = Math.min(1.0f, baseWeight + m);
			final float a = firstChannel.getf(i);
			final float b = secondChannel.getf(i);
			combined.setf(i, a * (1 - w) + b * w);
		}

		return combined;
	}
}
