package org.janelia.alignment.destreak;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.io.Serializable;

/**
 * This class detects streaks in an image and returns a corresponding mask.
 * <p>
 * The finder first applies a derivative filter in the x-direction to detect vertical edges. Then, it applies a mean
 * filter in the y-direction to smooth out the edges in the y-direction. The resulting image is then thresholded
 * (from above and below) to create a mask of the streaks. Finally, an optional Gaussian blur is applied to the mask to
 * smooth it. The mask is 0 where there are no streaks and 255 where there are streaks.
 * <p>
 * There are three parameters that can be set:
 * <ul>
 *     <li>meanFilterSize: the number of pixels to average in the y-direction (e.g., 0 means no averaging, 50 means averaging +/-50 pixels in y)</li>
 *     <li>threshold: the threshold used to convert the streak mask to a binary mask</li>
 *     <li>blurRadius: the radius of the Gaussian blur applied to the streak mask (0 means no smoothing)</li>
 * </ul>
 */
public class StreakFinder implements Serializable {

	private final int meanFilterSize;
	private final double threshold;
	private final int blurRadius;

	public StreakFinder(final int meanFilterSize, final double threshold, final int blurRadius) {
		if (meanFilterSize < 0) {
			throw new IllegalArgumentException("meanFilterSize must be non-negative");
		}
		if (threshold < 0) {
			throw new IllegalArgumentException("threshold must be non-negative");
		}
		if (blurRadius < 0) {
			throw new IllegalArgumentException("blurRadius must be 0 (no blur) or positive");
		}

		this.meanFilterSize = meanFilterSize;
		this.threshold = threshold;
		this.blurRadius = blurRadius;
	}

	public ImagePlus createStreakMask(final ImagePlus input) {
		ImageProcessor filtered = differenceFilterX(input.getProcessor());
		filtered = meanFilterY(filtered, meanFilterSize);
		filtered = bidirectionalThreshold(filtered, threshold);

		final ImagePlus mask = new ImagePlus("Mask", filtered);
		if (blurRadius > 0) {
			IJ.run(mask, "Gaussian Blur...", String.format("sigma=%d", blurRadius));
		}
		return mask;
	}

	private static ImageProcessor differenceFilterX(final ImageProcessor in) {
		final ImageProcessor out = new FloatProcessor(in.getWidth(), in.getHeight());
		final int width = in.getWidth();
		final int height = in.getHeight();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				final float left = in.getf(projectPeriodically(x - 1, width), y);
				final float right = in.getf(projectPeriodically(x + 1, width), y);
				out.setf(x, y, (right - left) / 2);
			}
		}
		return out;
	}

	private static ImageProcessor meanFilterY(final ImageProcessor in, final int size) {
		final ImageProcessor out = new FloatProcessor(in.getWidth(), in.getHeight());
		final int width = in.getWidth();
		final int height = in.getHeight();
		final int n = 2 * size + 1;

		for (int x = 0; x < width; x++) {
			// initialize running sum
			float sum = in.getf(x, 0);
			for (int y = 1; y <= size; y++) {
				sum += 2 * in.getf(x, y);
			}
			out.setf(x, 0, sum / n);

			// update running sum by adding the next value and subtracting the oldest value
			for (int y = 1; y < height; y++) {
				final float oldest = in.getf(x, projectPeriodically(y - size - 1, height));
				final float newest = in.getf(x, projectPeriodically(y + size, height));
				sum += newest - oldest;
				out.setf(x, y, sum / n);
			}
		}
		return out;
	}

	private static ImageProcessor bidirectionalThreshold(final ImageProcessor in, final double threshold) {
		final ImageProcessor out = new FloatProcessor(in.getWidth(), in.getHeight());
		final int width = in.getWidth();
		final int height = in.getHeight();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				final float value = Math.abs(in.getf(x, y));
				out.setf(x, y, (value > threshold) ? 255 : 0);
			}
		}
		return out;
	}

	private static int projectPeriodically(final int index, final int max) {
		if (index < 0) {
			return -index;
		} else if (index >= max) {
			return 2 * max - index - 2;
		} else {
			return index;
		}
	}
}
