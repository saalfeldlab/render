package org.janelia.alignment.destreak;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Prefs;

public class StreakFinder {
	private static final int MEAN_FILTER_SIZE = 201;

	public static void main(final String[] args) {
		final String srcPath = "/home/innerbergerm@hhmi.org/big-data/streak-correction/jrc_mus-liver-zon-3/z00032-0-0-1.png";

		final ImagePlus backup = new ImagePlus(srcPath);
		final ImagePlus mask = createStreakMask(backup);

		new ImageJ();
		mask.show();
		backup.show();
	}

	private static ImagePlus createStreakMask(final ImagePlus input) {
		final String meanFilterCoefficients = "text1=" + "1\n".repeat(MEAN_FILTER_SIZE) + " normalize";
		final ImagePlus converted = new ImagePlus("mask", input.getProcessor().convertToFloatProcessor());

		IJ.run(converted, "Convolve...", "text1=[-1 0 1\n] normalize");
		IJ.run(converted, "Convolve...", meanFilterCoefficients);
		IJ.setAutoThreshold(converted, "Default dark no-reset");
		IJ.setThreshold(converted, -10.0000, 10.0000);
		Prefs.blackBackground = false;
		IJ.run(converted, "Convert to Mask", "");
		IJ.run(converted, "Gaussian Blur...", "sigma=3");
		return converted;
	}
}
