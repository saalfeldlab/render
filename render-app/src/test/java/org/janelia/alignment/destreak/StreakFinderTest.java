package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

public class StreakFinderTest {
	public static void main(final String[] args) {
		final StreakFinder finder = new StreakFinder(201, 10.0, 3);
		final String srcPath = "/home/innerbergerm@hhmi.org/big-data/streak-correction/jrc_mus-liver-zon-3/z00032-0-0-1.png";

		final ImagePlus backup = new ImagePlus(srcPath);
		final ImagePlus corrected = new ImagePlus(srcPath);
		final ImagePlus mask = finder.createStreakMask(backup);

		new ImageJ();
		mask.show();
		backup.show();

		final StreakCorrector corrector = new SmoothMaskStreakCorrector(12, 6161, 8190, 10, 10, 0);
		corrector.process(corrected.getProcessor(), 1.0);

		final ImageProcessor correctedProcessor = corrected.getProcessor();
		final ImageProcessor maskProcessor = mask.getProcessor();
		final ImageProcessor backupProcessor = backup.getProcessor();
		for (int i = 0; i < corrected.getWidth() * corrected.getHeight(); i++) {
			final float lambda = maskProcessor.getf(i) / 255.0f;
			final float mergedValue = backupProcessor.getf(i) * lambda + correctedProcessor.getf(i) * (1.0f - lambda);
			correctedProcessor.setf(i, mergedValue);
		}
		corrected.show();
	}
}
