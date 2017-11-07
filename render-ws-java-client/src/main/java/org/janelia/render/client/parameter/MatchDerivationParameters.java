package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.match.ModelType;

/**
 * Parameters for point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchDerivationParameters implements Serializable {

    @Parameter(
            names = "--SIFTfdSize",
            description = "SIFT feature descriptor size: how many samples per row and column",
            required = false)
    public Integer fdSize = 8;

    @Parameter(
            names = "--SIFTminScale",
            description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale",
            required = false)
    public Double minScale = 0.5;

    @Parameter(
            names = "--SIFTmaxScale",
            description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale",
            required = false)
    public Double maxScale = 0.85;

    @Parameter(
            names = "--SIFTsteps",
            description = "SIFT steps per scale octave",
            required = false)
    public Integer steps = 3;

    @Parameter(
            names = "--matchRod",
            description = "Ratio of distances for matches",
            required = false)
    public Float matchRod = 0.92f;

    @Parameter(
            names = "--matchModelType",
            description = "Type of model for match filtering",
            required = false)
    public ModelType matchModelType = ModelType.AFFINE;

    @Parameter(
            names = "--matchIterations",
            description = "Match filter iterations",
            required = false)
    public Integer matchIterations = 1000;

    @Parameter(
            names = "--matchMaxEpsilon",
            description = "Minimal allowed transfer error for match filtering",
            required = false)
    public Float matchMaxEpsilon = 20.0f;

    @Parameter(
            names = "--matchMinInlierRatio",
            description = "Minimal ratio of inliers to candidates for match filtering",
            required = false)
    public Float matchMinInlierRatio = 0.0f;

    @Parameter(
            names = "--matchMinNumInliers",
            description = "Minimal absolute number of inliers for match filtering",
            required = false)
    public Integer matchMinNumInliers = 4;

    @Parameter(
            names = "--matchMaxNumInliers",
            description = "Maximum number of inliers for match filtering",
            required = false)
    public Integer matchMaxNumInliers;

    @Parameter(
            names = "--matchMaxTrust",
            description = "Reject match candidates with a cost larger than maxTrust * median cost",
            required = false)
    public Double matchMaxTrust = 3.0;

    @Parameter(
            names = { "--maxFeatureCacheGb", "--maxImageCacheGb" },
            description = "Maximum number of gigabytes of features (or DMesh images) to cache",
            required = false)
    public Integer maxCacheGb = 2;

}
