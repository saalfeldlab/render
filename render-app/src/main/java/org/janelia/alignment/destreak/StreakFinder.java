package org.janelia.alignment.destreak;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;

import java.io.Serializable;

/**
 * This class can detect streaks in an image and return a corresponding mask.
 * <p>
 * The finder first applies a derivative filter in the x-direction to detect vertical edges. Then, it applies a mean
 * filter in the y-direction to smooth out the edges in the y-direction. The resulting image is then thresholded
 * (from above and below) to create a mask of the streaks. Finally, a Gaussian blur is applied to the mask to smooth it.
 * The mask is 0 where there are no streaks and 255 where there are streaks. Note that there is typically a small region
 * at the top and bottom of the image where the mask is not reliable.
 * <p>
 * There are three parameters that can be set:
 * <ul>
 *     <li>meanFilterSize: the number of pixels to average in the y-direction (must be odd)</li>
 *     <li>threshold: the threshold used to convert the streak mask to a binary mask</li>
 *     <li>blurRadius: the radius of the Gaussian blur applied to the streak mask</li>
 * </ul>
 */
public class StreakFinder implements Serializable {

	private final double threshold;
	private final int blurRadius;
	private final String meanFilterCoefficients;

	public StreakFinder(final int meanFilterSize, final double threshold, final int blurRadius) {
		if (threshold < 1) {
			throw new IllegalArgumentException("threshold must be positive");
		}
		if (blurRadius < 1) {
			throw new IllegalArgumentException("blurRadius must be positive");
		}
		if (meanFilterSize < 1 || meanFilterSize % 2 == 0) {
			throw new IllegalArgumentException("meanFilterSize must be positive and odd");
		}
		
		this.threshold = threshold;
		this.blurRadius = blurRadius;
		meanFilterCoefficients = "text1=" + "1\n".repeat(meanFilterSize) + " normalize";
	}

	public ImagePlus createStreakMask(final ImagePlus input) {
		final ImagePlus converted = new ImagePlus("Mask", input.getProcessor().convertToFloatProcessor());

		IJ.run(converted, "Convolve...", "text1=[-1 0 1\n] normalize");
		IJ.run(converted, "Convolve...", meanFilterCoefficients);
		IJ.setAutoThreshold(converted, "Default dark no-reset");
		IJ.setThreshold(converted, -threshold, threshold);
		Prefs.blackBackground = false;
		IJ.run(converted, "Convert to Mask", "");
		IJ.run(converted, "Gaussian Blur...", String.format("sigma=%d", blurRadius));
		return converted;
	}
}
