package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.match.CanvasFeatureMatcher;
import org.janelia.alignment.match.ModelType;

/**
 * Parameters for point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchDerivationParameters implements Serializable {

    @Parameter(
            names = "--matchRod",
            description = "Ratio of distances for matches"
    )
    public Float matchRod = 0.92f;

    @Parameter(
            names = "--matchModelType",
            description = "Type of model for match filtering"
    )
    public ModelType matchModelType = ModelType.AFFINE;

    @Parameter(
            names = "--matchIterations",
            description = "Match filter iterations"
    )
    public Integer matchIterations = 1000;

    @Parameter(
            names = "--matchMaxEpsilon",
            description = "Minimal allowed transfer error for match filtering"
    )
    public Float matchMaxEpsilon = 20.0f;

    @Parameter(
            names = "--matchMinInlierRatio",
            description = "Minimal ratio of inliers to candidates for match filtering"
    )
    public Float matchMinInlierRatio = 0.0f;

    @Parameter(
            names = "--matchMinNumInliers",
            description = "Minimal absolute number of inliers for match filtering"
    )
    public Integer matchMinNumInliers = 4;

    @Parameter(
            names = "--matchMaxNumInliers",
            description = "Maximum number of inliers for match filtering"
    )
    public Integer matchMaxNumInliers;

    @Parameter(
            names = "--matchMaxTrust",
            description = "Reject match candidates with a cost larger than maxTrust * median cost"
    )
    public Double matchMaxTrust = 3.0;

    @Parameter(
            names = "--matchFilter",
            description = "Identifies if and how matches should be filtered"
    )
    public CanvasFeatureMatcher.FilterType matchFilter = CanvasFeatureMatcher.FilterType.SINGLE_SET;

}
