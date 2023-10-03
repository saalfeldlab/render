package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import java.io.Serializable;

import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.AffineIntensityCorrectionStrategy;

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

	@ParametersDelegate
	public ZDistanceParameters zDistance = new ZDistanceParameters();

	@Parameter(
			names = { "--numCoefficients" },
			description = "Number of correction coefficients to derive in each dimension " +
					"(e.g. value of 8 will divide each tile into 8x8 = 64 sub-regions)"
	)
	public int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

	@Parameter(
			names = "--equilibrateIntensities",
			description = "Apply translation to every coefficient tile to make the mean intensity equal to this value (default: null)",
			arity = 0)
	public boolean preEquilibrateIntensity = false;


	public void initDefaultValues() throws IllegalArgumentException {
		this.zDistance.initDefaultValues();
	}

}
