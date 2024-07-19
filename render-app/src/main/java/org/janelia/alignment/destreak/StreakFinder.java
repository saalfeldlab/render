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
		final ImagePlus imp = maskStreaks(srcPath);

		new ImageJ();
		imp.show();
		backup.show();
	}

	private static ImagePlus maskStreaks(final String srcPath) {
		final ImagePlus imp = new ImagePlus(srcPath);
		final String meanFilterCoefficients = "text1=" + "1\n".repeat(MEAN_FILTER_SIZE) + " normalize";

		IJ.run(imp, "32-bit", "");
		IJ.run(imp, "Convolve...", "text1=[-1 0 1\n] normalize");
		IJ.run(imp, "Convolve...", meanFilterCoefficients);
		IJ.setAutoThreshold(imp, "Default dark no-reset");
		IJ.setThreshold(imp, -10.0000, 10.0000);
		Prefs.blackBackground = false;
		IJ.run(imp, "Convert to Mask", "");
		IJ.run(imp, "Gaussian Blur...", "sigma=3");
		return imp;
	}
}
