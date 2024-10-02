package org.janelia.render.client;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import org.janelia.alignment.inpainting.RandomRayDirection2D;
import org.janelia.alignment.inpainting.RayCastingInpainter;

public class InpaintingClient {

	private static final int N_RAYS = 256;
	private static final int MAX_INPAINTING_DIAMETER = 100;
	private static final double THRESHOLD = 20.0;

	private static final int Y_MIN = 3826;
	private static final int Y_MAX = 3856;

	public static void main(final String[] args) {
		final String srcPath = "/home/innerbergerm@hhmi.org/big-data/streak-correction/jrc_P3-E2-D1-Lip4-19/z14765-0-0-0.png";
		final ImagePlus original = new ImagePlus(srcPath);
		final RayCastingInpainter inpainter = new RayCastingInpainter(N_RAYS, MAX_INPAINTING_DIAMETER, new RandomRayDirection2D());

//		final ImagePlus mask = threshold(original.getProcessor(), THRESHOLD);
		final ImagePlus mask = bandMask(original.getProcessor(), Y_MIN, Y_MAX);

		final long start = System.currentTimeMillis();
		final ImagePlus corrected = streakCorrectInpainting(inpainter, original, mask);
		System.out.println("Processing time: " + (System.currentTimeMillis() - start) + "ms");

		new ImageJ();
		original.show();
		mask.show();
		corrected.show();
	}

	private static ImagePlus threshold(final ImageProcessor in, final double threshold) {
		final ImageProcessor out = new FloatProcessor(in.getWidth(), in.getHeight());
		final int width = in.getWidth();
		final int height = in.getHeight();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				final float value = in.getf(x, y);
				out.setf(x, y, (value < threshold) ? 255 : 0);
			}
		}
		return new ImagePlus("Mask", out);
	}

	private static ImagePlus bandMask(final ImageProcessor in, final int yMin, final int yMax) {
		final int width = in.getWidth();
		final int height = in.getHeight();
		final ImageProcessor out = new FloatProcessor(width, height);

		for (int y = yMin; y <= yMax; y++) {
			for (int x = 0; x < width; x++) {
				out.setf(x, y, 255);
			}
		}
		return new ImagePlus("Mask", out);
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
