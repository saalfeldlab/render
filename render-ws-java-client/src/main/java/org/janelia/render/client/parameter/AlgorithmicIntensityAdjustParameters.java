package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.AffineIntensityCorrectionStrategy;

import java.io.Serializable;

/**
 * Abstract algorithmic parameters for intensity adjustment.
 *
 * @author Michael Innerberger
 */
@Parameters
public class AlgorithmicIntensityAdjustParameters implements Serializable {
	@Parameter(
			names = "--stack",
			description = "Stack name",
			required = true)
	public String stack;

	@Parameter(
			names = "--lambda1",
			description = "First lambda for strategy model")
	public Double lambda1 = AffineIntensityCorrectionStrategy.DEFAULT_LAMBDA;

	@Parameter(
			names = "--lambda2",
			description = "Second lambda for strategy model")
	public Double lambda2 = AffineIntensityCorrectionStrategy.DEFAULT_LAMBDA;

	@Parameter(
			names = { "--maxPixelCacheGb" },
			description = "Maximum number of gigabytes of pixels to cache"
	)
	public Integer maxPixelCacheGb = 1;

	@Parameter(
			names = "--renderScale",
			description = "Scale for rendered tiles used during intensity comparison")
	public double renderScale = 0.1;

	@Parameter(
			names = "--zDistance",
			description = "If specified, apply correction across this many z-layers from the current z-layer " +
					"(omit to only correct in 2D)")
	public Integer zDistance;

	@Parameter(
			names = { "--numCoefficients" },
			description = "Number of correction coefficients to derive in each dimension " +
					"(e.g. value of 8 will divide each tile into 8x8 = 64 sub-regions)"
	)
	public int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;
}
