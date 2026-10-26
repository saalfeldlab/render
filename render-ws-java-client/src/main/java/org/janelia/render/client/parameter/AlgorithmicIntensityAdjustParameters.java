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
			description = "Maximum number of gigabytes of pixels to cache (default: 1Gb)"
	)
	public Integer maxPixelCacheGb = 1;

	@Parameter(
			names = "--renderScale",
			description = "Scale for rendered tiles in the same layer used during intensity comparison (default: 0.1)")
	public double renderScale = 0.1;

	@Parameter(
			names = "--crossLayerRenderScale",
			description = "Scale for rendered tiles in different layers used during intensity comparison. If not given, the same scale as --renderScale is used.")
	public Double crossLayerRenderScale = null;

	@ParametersDelegate
	public ZDistanceParameters zDistance = new ZDistanceParameters();

	@Parameter(
			names = { "--numCoefficients" },
			description = "Number of correction coefficients to derive in each dimension " +
					"(e.g. value of 8 will divide each tile into 8x8 = 64 sub-regions)"
	)
	public int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

	@Parameter(
			names = "--equilibrationWeight",
			description = "Apply equilibration to every coefficient tile to make the mean intensity more uniform (default: 0.0 = no equilibration)"
	)
	public double equilibrationWeight = 0.0;

	@Parameter(
			names = "--maxAllowedError",
			description = "Max allowed error for the optimizer"
	)
	public Double maxAllowedError = 0.1;

	@Parameter(
			names = "--maxIterations",
			description = "Max iterations for the optimizer"
	)
	public Integer maxIterations = 10000;

	@Parameter(
			names = "--maxPlateauWidth",
			description = "Max plateau width for the optimizer"
	)
	public Integer maxPlateauWidth = 500;

	@Parameter(
			names = "--useRansacMatching",
			description = "Match raw pixel intensities and use RANSAC to find inliers instead of histogram matching"
	)
	public boolean useRansacMatching = false;

	@Parameter(
			names = "--cacheRenderedTiles",
			description = "Trade memory for compute: cache each tile's fully rendered footprint and crop overlaps " +
					"from it (less compute, more memory) instead of caching only the downsampled source and " +
					"re-rendering each overlap box (more compute, less memory)"
	)
	public boolean cacheRenderedTiles = false;

	@Parameter(
			names = "--tetherCrossLayers",
			description = "If set, cross-layer coefficient tiles are only tethered at pixels whose intensities already " +
					"coincide (matches with a non-zero shift are dropped). This smooths intensities across z without " +
					"introducing correction on top of the tether, avoiding systematically wrong matches at " +
					"tissue-to-resin transitions in z. Forces pixel-by-pixel matching. Default: false."
	)
	public boolean tetherCrossLayers = false;

	@Parameter(
			names = "--mfovSeamMatchWeight",
			description = "Multi-SEM only: weight of in-layer matches across the top/bottom seam of two MFOVs " +
					"(top rows 0-1 of one MFOV against bottom rows 9-10 of another), where the tissue is exposed twice. " +
					"A weight of 0 drops these matches. Default: matches are weighted like all others."
	)
	public Double mfovSeamMatchWeight = null;

	@Parameter(
			names = "--mfovSeamShift",
			description = "Multi-SEM only: additive constant (8-bit gray levels) applied to the intensities of the " +
					"top-of-MFOV tile when matching across a top/bottom MFOV seam (applied after --mfovSeamScale). " +
					"Default: no shift."
	)
	public Double mfovSeamShift = null;

	@Parameter(
			names = "--mfovSeamScale",
			description = "Multi-SEM only: multiplicative constant applied to the intensities of the top-of-MFOV tile " +
					"when matching across a top/bottom MFOV seam. Default: no scaling."
	)
	public Double mfovSeamScale = null;

	@Parameter(
			names = "--mfovSlope",
			description = "Multi-SEM only: linear ramp (8-bit gray levels from MFOV top to bottom) added to the fitted " +
					"intensity correction, centered so that the MFOV top gets +slope/2 and the bottom -slope/2. " +
					"Default: no ramp."
	)
	public Double mfovSlope = null;


	public void initDefaultValues() throws IllegalArgumentException {
		this.zDistance.initDefaultValues();
		if (crossLayerRenderScale == null) {
			crossLayerRenderScale = renderScale;
		}
	}

}
