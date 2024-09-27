package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import org.janelia.alignment.destreak.StreakFinder;

import java.io.Serializable;

/**
 * Parameters for streak finding with a {@link StreakFinder}.
 */
public class StreakFinderParameters implements Serializable {
	@Parameter(
			names = "--meanFilterSize",
			description = "Number of pixels to average in the positive and negative y-direction",
			required = true)
	public int meanFilterSize;

	@Parameter(
			names = "--threshold",
			description = "Threshold used to convert the streak mask to a binary mask",
			required = true)
	public double threshold;

	@Parameter(
			names = "--blurRadius",
			description = "Radius of the Gaussian blur applied to the streak mask",
			required = true)
	public int blurRadius;

	public StreakFinder createStreakFinder() {
		return new StreakFinder(meanFilterSize, threshold, blurRadius);
	}

	public void validate() {
		if (meanFilterSize < 0) {
			throw new IllegalArgumentException("meanFilterSize must be non-negative");
		}
		if (threshold < 0) {
			throw new IllegalArgumentException("threshold must be non-negative");
		}
		if (blurRadius < 0) {
			throw new IllegalArgumentException("blurRadius must be 0 (no blur) or positive");
		}
	}
}
