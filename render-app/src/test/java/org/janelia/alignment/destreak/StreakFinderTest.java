package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

public class StreakFinderTest {
	public static void main(final String[] args) {
		final StreakFinder finder = new StreakFinder(201, 10.0, 3);
		final StreakCorrector corrector = new SmoothMaskStreakCorrector(12, 6161, 8190, 10, 10, 0);
		final String srcPath = "/home/innerbergerm@hhmi.org/big-data/streak-correction/jrc_mus-liver-zon-3/z00032-0-0-1.png";

		final ImagePlus original = new ImagePlus(srcPath);
		final ImagePlus mask = finder.createStreakMask(original);
		final ImagePlus corrected = streakCorrectLocally(corrector, original, mask);

		new ImageJ();
		mask.show();
		original.show();
		corrected.show();
	}

	private static ImagePlus streakCorrectLocally(
			final StreakCorrector corrector,
			final ImagePlus original,
			final ImagePlus mask) {

		final ImagePlus corrected = original.duplicate();
		corrected.setTitle("Corrected");
		corrector.process(corrected.getProcessor(), 1.0);

		final ImageProcessor proc = corrected.getProcessor();
		final ImageProcessor maskProc = mask.getProcessor();
		final ImageProcessor originalProc = original.getProcessor();
		for (int i = 0; i < corrected.getWidth() * corrected.getHeight(); i++) {
			final float lambda = maskProc.getf(i) / 255.0f;
			final float mergedValue = originalProc.getf(i) * lambda + proc.getf(i) * (1.0f - lambda);
			proc.setf(i, mergedValue);
		}

		return corrected;
	}
}
