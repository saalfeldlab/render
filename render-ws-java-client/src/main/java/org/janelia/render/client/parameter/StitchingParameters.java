package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.RigidModel2D;
import mpicbg.models.TranslationModel2D;

import java.io.Serializable;

/**
 * Parameters for stitching alignment model, by TRANSLATION with tunable regularization --- with RIGID (default: 0.00)
 *
 * @author Michael Innerberger
 */
public class StitchingParameters implements Serializable {
	@Parameter(
			names = "--lambdaStitching",
			description = "Regularization parameter: translation model with rigid regularization (default:0.0)"
	)
	public Double lambda = 0.0;

	@Parameter(
			names = "--maxAllowedErrorStitching",
			description = "Max allowed error stitching (default:10.0)"
	)
	public Double maxAllowedError = 10.0;

	@Parameter(
			names = "--maxIterationsStitching",
			description = "Max iterations stitching (default:500)"
	)
	public Integer maxIterations = 500;

	@Parameter(
			names = "--maxPlateauWidthStitching",
			description = "Max plateau width stitching (default:50)"
	)
	public Integer maxPlateauWidth = 50;

	@Parameter(
			names = "--minStitchingInliers",
			description = "how many inliers per tile pair are necessary for stitching (default:25)"
	)
	public Integer minInliers = 25;

	public InterpolatedAffineModel2D<TranslationModel2D, RigidModel2D> getModel() {
		return new InterpolatedAffineModel2D<>(new TranslationModel2D(), new RigidModel2D(), lambda);
	}
}
