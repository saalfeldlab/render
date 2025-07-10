package org.janelia.render.client.parameter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.parameters.FeatureExtractionParameters;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.FeatureRenderParameters;
import org.janelia.alignment.match.parameters.GeometricDescriptorAndMatchFilterParameters;
import org.janelia.alignment.match.parameters.MatchCommonParameters;
import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.match.parameters.TilePairDerivationParameters;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;

/**
 * Parameters for building MFOV-as-tile images, matches, and stacks.
 */
public class MFOVAsTileParameters
        implements Serializable {

    private final Double mfovRenderScale;
    private final String mfovRootDirectory;
    private final String dynamicMfovStackSuffix;
    private final String renderedMfovStackSuffix;
    private final String alignedMfovStackSuffix;
    private final String roughSfovStackSuffix;

    public MFOVAsTileParameters() {
        this(null,
             null,
             null,
             null,
             null,
             null);
    }

    public MFOVAsTileParameters(final Double mfovRenderScale,
                                final String mfovRootDirectory,
                                final String dynamicMfovStackSuffix,
                                final String renderedMfovStackSuffix,
                                final String alignedMfovStackSuffix,
                                final String roughSfovStackSuffix) {
        this.mfovRenderScale = mfovRenderScale;
        this.mfovRootDirectory = mfovRootDirectory;
        this.dynamicMfovStackSuffix = dynamicMfovStackSuffix;
        this.renderedMfovStackSuffix = renderedMfovStackSuffix;
        this.alignedMfovStackSuffix = alignedMfovStackSuffix;
        this.roughSfovStackSuffix = roughSfovStackSuffix;
    }

    public Double getMfovRenderScale() {
        return mfovRenderScale;
    }

    public String getMfovRootDirectory() {
        return mfovRootDirectory;
    }

    public String getDynamicMfovStackSuffix() {
        return dynamicMfovStackSuffix;
    }

    public String getRenderedMfovStackSuffix() {
        return renderedMfovStackSuffix;
    }

    public String getAlignedMfovStackSuffix() {
        return alignedMfovStackSuffix;
    }

    public String getRoughSfovStackSuffix() {
        return roughSfovStackSuffix;
    }

    public String getAlignedMfovStackSuffixForRawSfovStack() {
        return dynamicMfovStackSuffix + renderedMfovStackSuffix + alignedMfovStackSuffix;
    }

    public StackId getDynamicMfovStackId(final StackId rawSfovStackId) {
        return rawSfovStackId.withStackSuffix(dynamicMfovStackSuffix);
    }

    public StackId getRenderedMfovStackId(final StackId dynamicMfovStackId) {
        return dynamicMfovStackId.withStackSuffix(renderedMfovStackSuffix);
    }

    public StackId getRoughSfovStackId(final StackId rawSfovStackId) {
        return rawSfovStackId.withStackSuffix(roughSfovStackSuffix);
    }

    public List<MatchRunParameters> buildMfovMatchRunList() {
        final List<MatchRunParameters> mfovMatchRunList = new ArrayList<>();
        mfovMatchRunList.add(buildMontageMatchRunParameters());
        mfovMatchRunList.add(buildCrossMatchRunParameters());
        return mfovMatchRunList;
    }

    public AffineBlockSolverSetup buildMfovAffineBlockSolverSetup() {

        final AffineBlockSolverSetup setup = new AffineBlockSolverSetup();

        setup.preAlign = FIBSEMAlignmentParameters.PreAlign.TRANSLATION;

        setup.distributedSolve.maxAllowedErrorGlobal = 10.0;
        setup.distributedSolve.maxIterationsGlobal = 1000;
        setup.distributedSolve.maxPlateauWidthGlobal = 200;
        setup.distributedSolve.threadsWorker = 1;
        setup.distributedSolve.threadsGlobal = 1;
        setup.distributedSolve.deriveThreadsUsingSparkConfig = true;

        setup.targetStack.stackSuffix = this.alignedMfovStackSuffix;
        setup.targetStack.completeStack = true;

        setup.blockPartition.sizeZ = 100; // must be greater than total number of layers in each mfov-as-tile stack

        setup.stitching.lambda = 0.0;
        setup.stitching.maxAllowedError = 0.5;
        setup.stitching.maxIterations = 5000;
        setup.stitching.maxPlateauWidth = 1000;
        setup.stitching.minInliers = 25;

        final List<Double> emptyDoubleList = List.of();
        final List<Integer> emptyIntegerList = List.of();

        setup.blockOptimizer.lambdasRigid = emptyDoubleList;
        setup.blockOptimizer.lambdasTranslation = emptyDoubleList;
        setup.blockOptimizer.lambdasRegularization = emptyDoubleList;
        setup.blockOptimizer.iterations = emptyIntegerList;
        setup.blockOptimizer.maxPlateauWidth = emptyIntegerList;
        setup.blockOptimizer.maxAllowedError = 5.0;

        setup.maxNumMatches = 0;

        setup.alternatingRuns.nRuns = 1;
        setup.alternatingRuns.keepIntermediateStacks = false;

        return setup;
    }

    private static MatchRunParameters buildMontageMatchRunParameters() {
        final List<MatchStageParameters> matchStageParametersList =
                List.of(new MatchStageParameters("montageMfovAsTilePass1",
                                                 buildFeatureRenderParameters(0.4), // 4 secs for 226 matches
                                                 new FeatureRenderClipParameters(800, 800),
                                                 buildFeatureExtractionParameters(),
                                                 buildFeatureMatchDerivation(100),
                                                 buildDisabledGeometricDescriptorAndMatch(),
                                                 null,
                                                 null),
                        new MatchStageParameters("montageMfovAsTilePass2",
                                                 buildFeatureRenderParameters(1.0), // 78 secs for 2500 matches
                                                 new FeatureRenderClipParameters(800, 800),
                                                 buildFeatureExtractionParameters(),
                                                 buildFeatureMatchDerivation(10),
                                                 buildDisabledGeometricDescriptorAndMatch(),
                                                 null,
                                                 null));
        return new MatchRunParameters("montageMfovAsTileRun",
                                      buildMatchCommonParameters(4),
                                      buildTilePairDerivationParameters(0.6, 0, false),
                                      matchStageParametersList);
    }

    private static MatchRunParameters buildCrossMatchRunParameters() {
        final List<MatchStageParameters> matchStageParametersList =
                List.of(new MatchStageParameters("crossMfovAsTilePass1",
                                                 buildFeatureRenderParameters(0.2), // 4 secs for 276 matches
                                                 new FeatureRenderClipParameters(),
                                                 buildFeatureExtractionParameters(),
                                                 buildFeatureMatchDerivation(150),
                                                 buildDisabledGeometricDescriptorAndMatch(),
                                                 null,
                                                 null),
                        new MatchStageParameters("crossMfovAsTilePass2",
                                                 buildFeatureRenderParameters(0.3), // 16 secs for 825 matches
                                                 new FeatureRenderClipParameters(),
                                                 buildFeatureExtractionParameters(),
                                                 buildFeatureMatchDerivation(10),
                                                 buildDisabledGeometricDescriptorAndMatch(),
                                                 null,
                                                 null));
        return new MatchRunParameters("crossMfovAsTileRun",
                                      buildMatchCommonParameters(10),
                                      buildTilePairDerivationParameters(0.1, 1, true),
                                      matchStageParametersList);
    }

    private static MatchCommonParameters buildMatchCommonParameters(final int maxPairsPerStackBatch) {
        final MatchCommonParameters matchCommon = new MatchCommonParameters();
        matchCommon.maxPairsPerStackBatch = maxPairsPerStackBatch;
        matchCommon.featureStorage.maxFeatureSourceCacheGb = 6;
        matchCommon.maxPeakCacheGb = 1;
        return matchCommon;
    }

    private static TilePairDerivationParameters buildTilePairDerivationParameters(final double xyNeighborFactor,
                                                                                  final int zNeighborDistance,
                                                                                  final boolean excludeSameLayerNeighbors) {
        final TilePairDerivationParameters tilePairDerivation = new TilePairDerivationParameters();
        tilePairDerivation.xyNeighborFactor = xyNeighborFactor;
        tilePairDerivation.useRowColPositions = false;
        tilePairDerivation.zNeighborDistance = zNeighborDistance;
        tilePairDerivation.excludeCornerNeighbors = false;
        tilePairDerivation.excludeCompletelyObscuredTiles = false;
        tilePairDerivation.excludeSameLayerNeighbors = excludeSameLayerNeighbors;
        tilePairDerivation.excludeSameSectionNeighbors = false;
        tilePairDerivation.excludeSameMfovNeighbors = false;
        tilePairDerivation.excludePairsInMatchCollection = "pairsFromPriorRuns";
        return tilePairDerivation;
    }

    private static FeatureRenderParameters buildFeatureRenderParameters(final double renderScale) {
        final FeatureRenderParameters featureRender = new FeatureRenderParameters();
        featureRender.renderScale = renderScale;
        featureRender.renderWithFilter = true;
        featureRender.renderWithoutMask = false;
        return featureRender;
    }

    private static FeatureExtractionParameters buildFeatureExtractionParameters() {
        final FeatureExtractionParameters featureExtraction = new FeatureExtractionParameters();
        featureExtraction.fdSize = 4;
        featureExtraction.maxScale = 1.0;
        featureExtraction.minScale = 0.125;
        featureExtraction.steps = 5;
        return featureExtraction;
    }

    private static MatchDerivationParameters buildFeatureMatchDerivation(final int matchMinNumInliers) {
        final MatchDerivationParameters featureMatchDerivation = new MatchDerivationParameters();
        featureMatchDerivation.matchFilter = MatchFilter.FilterType.SINGLE_SET;
        featureMatchDerivation.matchFullScaleCoverageRadius = 10.0;
        featureMatchDerivation.matchIterations = 1000;
        featureMatchDerivation.matchMaxEpsilonFullScale = 5.0f;
        featureMatchDerivation.matchMaxTrust = 4.0;
        featureMatchDerivation.matchMinCoveragePercentage = 0.0;
        featureMatchDerivation.matchMinInlierRatio = 0.0f;
        featureMatchDerivation.matchMinNumInliers = matchMinNumInliers;
        featureMatchDerivation.matchModelType = ModelType.TRANSLATION;
        featureMatchDerivation.matchRod = 0.92f;
        return featureMatchDerivation;
    }

    private static GeometricDescriptorAndMatchFilterParameters buildDisabledGeometricDescriptorAndMatch() {
        final GeometricDescriptorAndMatchFilterParameters gdParams = new GeometricDescriptorAndMatchFilterParameters();
        gdParams.gdEnabled = false;
        return gdParams;
    }
}
