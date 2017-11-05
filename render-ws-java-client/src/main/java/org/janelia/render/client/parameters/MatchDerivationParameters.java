package org.janelia.render.client.parameters;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.alignment.match.ModelType;

/**
 * Client parameters for point match derivation.
 *
 * @author Eric Trautman
 */
public class MatchDerivationParameters implements Serializable {

    @Parameter(
            names = "--SIFTfdSize",
            description = "SIFT feature descriptor size: how many samples per row and column",
            required = false,
            order = 30)
    public Integer fdSize = 8;

    @Parameter(
            names = "--SIFTminScale",
            description = "SIFT minimum scale: minSize * minScale < size < maxSize * maxScale",
            required = false,
            order = 31)
    public Double minScale = 0.5;

    @Parameter(
            names = "--SIFTmaxScale",
            description = "SIFT maximum scale: minSize * minScale < size < maxSize * maxScale",
            required = false,
            order = 32)
    public Double maxScale = 0.85;

    @Parameter(
            names = "--SIFTsteps",
            description = "SIFT steps per scale octave",
            required = false,
            order = 33)
    public Integer steps = 3;

    @Parameter(
            names = "--matchRod",
            description = "Ratio of distances for matches",
            required = false,
            order = 40)
    public Float matchRod = 0.92f;

    @Parameter(
            names = "--matchModelType",
            description = "Type of model for match filtering",
            required = false,
            order = 41)
    public ModelType matchModelType = ModelType.AFFINE;

    @Parameter(
            names = "--matchIterations",
            description = "Match filter iterations",
            required = false,
            order = 42)
    public Integer matchIterations = 1000;

    @Parameter(
            names = "--matchMaxEpsilon",
            description = "Minimal allowed transfer error for match filtering",
            required = false,
            order = 43)
    public Float matchMaxEpsilon = 20.0f;

    @Parameter(
            names = "--matchMinInlierRatio",
            description = "Minimal ratio of inliers to candidates for match filtering",
            required = false,
            order = 44)
    public Float matchMinInlierRatio = 0.0f;

    @Parameter(
            names = "--matchMinNumInliers",
            description = "Minimal absolute number of inliers for match filtering",
            required = false,
            order = 45)
    public Integer matchMinNumInliers = 4;

    @Parameter(
            names = "--matchMaxNumInliers",
            description = "Maximum number of inliers for match filtering",
            required = false,
            order = 46)
    public Integer matchMaxNumInliers;

    @Parameter(
            names = "--matchMaxTrust",
            description = "Reject match candidates with a cost larger than maxTrust * median cost",
            required = false,
            order = 47)
    public Double matchMaxTrust = 3.0;

    @Parameter(
            names = { "--maxFeatureCacheGb", "--maxImageCacheGb" },
            description = "Maximum number of gigabytes of features (or DMesh images) to cache",
            required = false,
            order = 50)
    public Integer maxCacheGb = 2;

}
