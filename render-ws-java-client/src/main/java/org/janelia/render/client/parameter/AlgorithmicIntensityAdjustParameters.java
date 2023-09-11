package org.janelia.render.client.parameter;

import com.beust.jcommander.IValueValidator;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import org.janelia.render.client.intensityadjust.AdjustBlock;
import org.janelia.render.client.intensityadjust.AffineIntensityCorrectionStrategy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
			description = "Comma separated list of all relative distances of z-layers for which to apply correction. " +
					"The current z-layer has relative distance 0 and is always corrected. " +
					"(Omit this parameter to only correct in 2D)",
			validateValueWith = ZDistanceValidator.class)
	public List<Integer> zDistance = new ArrayList<>();

	@Parameter(
			names = { "--numCoefficients" },
			description = "Number of correction coefficients to derive in each dimension " +
					"(e.g. value of 8 will divide each tile into 8x8 = 64 sub-regions)"
	)
	public int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;


	public static class ZDistanceValidator implements IValueValidator<List<Integer>> {
		@Override
		public void validate(final String name, final List<Integer> value) {
			if (value.stream().anyMatch(z -> z < 0))
				throw new ParameterException("Parameter --zDistance must not contain negative values");

			if (value.size() == 1) {
				// include whole range up to given value
				value.addAll(IntStream.range(1, value.get(0)).boxed().collect(Collectors.toList()));
			}

			if (!value.contains(0))
				value.add(0);
			value.sort(Integer::compareTo);
		}
	}
}
