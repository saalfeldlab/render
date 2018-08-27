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

    public MatchDerivationParameters() {
        setDefaults();
    }

    @Parameter(
            names = "--matchRod",
            description = "Ratio of distances for matches"
    )
    public Float matchRod;

    @Parameter(
            names = "--matchModelType",
            description = "Type of model for match filtering"
    )
    public ModelType matchModelType;

    @Parameter(
            names = "--matchIterations",
            description = "Match filter iterations"
    )
    public Integer matchIterations;

    @Parameter(
            names = "--matchMaxEpsilon",
            description = "Minimal allowed transfer error for match filtering"
    )
    public Float matchMaxEpsilon;

    @Parameter(
            names = "--matchMinInlierRatio",
            description = "Minimal ratio of inliers to candidates for match filtering"
    )
    public Float matchMinInlierRatio;

    @Parameter(
            names = "--matchMinNumInliers",
            description = "Minimal absolute number of inliers for match filtering"
    )
    public Integer matchMinNumInliers;

    @Parameter(
            names = "--matchMaxNumInliers",
            description = "Maximum number of inliers for match filtering"
    )
    public Integer matchMaxNumInliers;

    @Parameter(
            names = "--matchMaxTrust",
            description = "Reject match candidates with a cost larger than maxTrust * median cost"
    )
    public Double matchMaxTrust;

    @Parameter(
            names = "--matchFilter",
            description = "Identifies if and how matches should be filtered"
    )
    public CanvasFeatureMatcher.FilterType matchFilter = CanvasFeatureMatcher.FilterType.SINGLE_SET;

    void setDefaults() {
        if (matchRod == null) {
            matchRod = 0.92f;
        }
        if (matchModelType == null) {
            matchModelType = ModelType.AFFINE;
        }
        if (matchIterations == null) {
            matchIterations = 1000;
        }
        if (matchMaxEpsilon == null) {
            matchMaxEpsilon = 20.0f;
        }
        if (matchMinInlierRatio == null) {
            matchMinInlierRatio = 0.0f;
        }
        if (matchMinNumInliers == null) {
            matchMinNumInliers = 4;
        }
        if (matchMaxTrust == null) {
            matchMaxTrust = 3.0;
        }
    }

}
