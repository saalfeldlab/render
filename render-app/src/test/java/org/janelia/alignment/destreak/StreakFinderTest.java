package org.janelia.alignment.destreak;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import org.janelia.alignment.inpainting.RandomRayDirection2D;
import org.janelia.alignment.inpainting.RayCastingInpainter;

public class StreakFinderTest {
	public static void main(final String[] args) {
		final String srcPath = "/home/innerbergerm@hhmi.org/big-data/streak-correction/jrc_mus-liver-zon-3/z00032-0-0-1.png";
		final StreakFinder finder = new StreakFinder(100, 5.0, 3);
		// final StreakCorrector corrector = new SmoothMaskStreakCorrector(12, 6161, 8190, 10, 10, 0);
		final RayCastingInpainter inpainter = new RayCastingInpainter(128, 100, new RandomRayDirection2D());

		final long start = System.currentTimeMillis();
		final ImagePlus original = new ImagePlus(srcPath);
		final ImagePlus mask = finder.createStreakMask(original);
		// final ImagePlus corrected = streakCorrectFourier(corrector, original, mask);
		final ImagePlus corrected = streakCorrectInpainting(inpainter, original, mask);
		System.out.println("Processing time: " + (System.currentTimeMillis() - start) + "ms");

		new ImageJ();
		mask.show();
		original.show();
		corrected.show();
	}

	private static ImagePlus streakCorrectFourier(
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
			final float mergedValue = originalProc.getf(i) * (1 - lambda) + proc.getf(i) * lambda;
			proc.setf(i, mergedValue);
		}

		return corrected;
	}

	private static ImagePlus streakCorrectInpainting(
			final RayCastingInpainter inpainter,
			final ImagePlus original,
			final ImagePlus mask) {

		final FloatProcessor correctedFp = original.getProcessor().convertToFloatProcessor();
		final ImagePlus corrected = new ImagePlus("Corrected", correctedFp);
		final Img<FloatType> correctedImg = ImageJFunctions.wrapFloat(corrected);

		final FloatProcessor maskFp = mask.getProcessor().convertToFloatProcessor();
		final ImagePlus maskIp = new ImagePlus("Mask", maskFp);
		final Img<FloatType> maskImg = ImageJFunctions.wrapFloat(maskIp);

		inpainter.inpaint(correctedImg, maskImg);

		return corrected;
	}
}
