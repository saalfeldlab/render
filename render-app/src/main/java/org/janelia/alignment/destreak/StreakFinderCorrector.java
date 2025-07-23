package org.janelia.alignment.destreak;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.util.ImageProcessorCache;

public class StreakFinderCorrector {

	public static void main(final String[] args) {
		// Paths to the two channels
		final String path = "file:///Users/innerbergerm/Data/streak-correction/second-channel-experiments/jrc_mpi_5g_1_hc_z7000.h5";
		final String firstChannelPath = path + "?dataSet=/0-0-0/c0";
		final String secondChannelPath = path + "?dataSet=/0-0-0/c1";

		// Load and display the two channels
		final ImagePlus firstChannel = new ImagePlus("First", load(firstChannelPath));
		final ImagePlus secondChannel = new ImagePlus("Second", load(secondChannelPath));

		// shift second channel to match intensity of first channel and smooth it with median filter
//		final ImageStatistics firstChannelStats = firstChannel.getStatistics();
//		final ImageStatistics secondChannelStats = secondChannel.getStatistics();
//		final float shift = (float) (firstChannelStats.mean - secondChannelStats.mean);
		matchHistograms(firstChannel, secondChannel);
		IJ.run(secondChannel, "Gaussian Blur...", "sigma=0.7");

//		IJ.run(secondChannel, "Add...", String.format("value=%.2f", shift));
//		IJ.run(secondChannel, "Median...", "radius=1");
//		IJ.run(secondChannel, "Mean...", "radius=1");

		new ImageJ();
		firstChannel.show();
		secondChannel.show();

		// Create a streak mask
		final StreakFinder finder = new StreakFinder(100, 100, 5);
		final ImagePlus mask = finder.createStreakMask(firstChannel);

		mask.setTitle("Streak Mask");
		mask.show();

		// Combine the two channels using the mask
		final ImagePlus combined = new ImagePlus(
				"Combined",
				combine(firstChannel.getProcessor(), secondChannel.getProcessor(), mask.getProcessor(),
//						(a, b, m) -> m) // Naive combination
//						(a, b, m) -> Math.min(1, m / 0.8f)) // Saturate mask
						(a, b, m) -> Math.min(1, m + 0.3f)) // Add constant part of second channel (works best so far)
//						(a, b, m) -> (float) Math.sqrt(m * Math.exp(0.001 * (firstChannelStats.min - a)))) // Streak-aware weight
		);
		combined.show();
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
										  final WeightFunction weight) {
		final ImageProcessor combined = firstChannel.convertToFloat().duplicate();
		final int n = firstChannel.getPixelCount();

		for (int i = 0; i < n; ++i) {
			final float m = mask.getf(i) / 255.0f;
			final float a = firstChannel.getf(i);
			final float b = secondChannel.getf(i);
			final float w = weight.get(a, b, m);
			combined.setf(i, a * (1 - w) + b * w);
		}

		return combined;
	}

	private interface WeightFunction {
		float get(float first, float second, float maskValue);
	}
}
