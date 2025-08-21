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
    private final String prealignedMfovStackSuffix;
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
             null,
             null);
    }

    public MFOVAsTileParameters(final Double mfovRenderScale,
                                final String mfovRootDirectory,
                                final String prealignedMfovStackSuffix,
                                final String dynamicMfovStackSuffix,
                                final String renderedMfovStackSuffix,
                                final String alignedMfovStackSuffix,
                                final String roughSfovStackSuffix) {
        this.mfovRenderScale = mfovRenderScale;
        this.mfovRootDirectory = mfovRootDirectory;
        this.prealignedMfovStackSuffix = prealignedMfovStackSuffix;
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

    public String getPrealignedMfovStackSuffix() {
        return prealignedMfovStackSuffix;
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

    public String getRenderedMfovStackSuffixForRawSfovStack() {
        return dynamicMfovStackSuffix + renderedMfovStackSuffix;
    }

    public String getAlignedMfovStackSuffixForRawSfovStack() {
        return getRenderedMfovStackSuffixForRawSfovStack() + alignedMfovStackSuffix;
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

    public boolean doPrealign() {
        return prealignedMfovStackSuffix != null;
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
        setup.stitching.maxAllowedError = 10.0;
        setup.stitching.maxIterations = 5000;
        setup.stitching.maxPlateauWidth = 1000;
        setup.stitching.minInliers = 25;

        // 20250815 parameters - allowed shifts between z 10 and 11
//        setup.blockOptimizer.lambdasRigid = List.of(1.0);
//        setup.blockOptimizer.lambdasTranslation = List.of(0.5);
//        setup.blockOptimizer.lambdasRegularization = List.of(0.05);
//        setup.blockOptimizer.iterations = List.of(10000);
//        setup.blockOptimizer.maxPlateauWidth = List.of(1000);
//        setup.blockOptimizer.maxAllowedError = 10.0;

        // test_b_twelve_mfovs_align_20250818_092149a - introduces rotation
//        setup.blockOptimizer.lambdasRigid = List.of(1.0,1.0,0.9,0.3,0.01);
//        setup.blockOptimizer.lambdasTranslation = List.of(1.0,0.0,0.0,0.0,0.0);
//        setup.blockOptimizer.lambdasRegularization = List.of(0.05, 0.01, 0.0, 0.0, 0.0);
//        setup.blockOptimizer.iterations = List.of(1000,1000,500,250,250);
//        setup.blockOptimizer.maxPlateauWidth = List.of(250,250,150,100,100);
//        setup.blockOptimizer.maxAllowedError = 10.0;

        // test_b_twelve_mfovs_align_20250818_092850a
//        setup.blockOptimizer.lambdasRigid = List.of(1.0,1.0,0.9,0.3,0.01);
//        setup.blockOptimizer.lambdasTranslation = List.of(1.0,0.0,0.0,0.0,0.0);
//        setup.blockOptimizer.lambdasRegularization = List.of(0.0, 0.0, 0.0, 0.0, 0.0);
//        setup.blockOptimizer.iterations = List.of(1000,1000,500,250,250);
//        setup.blockOptimizer.maxPlateauWidth = List.of(250,250,150,100,100);
//        setup.blockOptimizer.maxAllowedError = 10.0;

        // test_c_24_mfovs_align_20250818_153209a
        setup.blockOptimizer.lambdasRigid = List.of(1.0,1.0,0.9,0.3,0.01);
        setup.blockOptimizer.lambdasTranslation = List.of(1.0,1.0,1.0,1.0,1.0);
        setup.blockOptimizer.lambdasRegularization = List.of(0.0, 0.0, 0.0, 0.0, 0.0);
        setup.blockOptimizer.iterations = List.of(1000,1000,500,250,250);
        setup.blockOptimizer.maxPlateauWidth = List.of(250,250,150,100,100);
        setup.blockOptimizer.maxAllowedError = 10.0;

        setup.maxNumMatches = 0;

        setup.alternatingRuns.nRuns = 1;
        setup.alternatingRuns.keepIntermediateStacks = false;

        return setup;
    }

    private static MatchRunParameters buildMontageMatchRunParameters() {
        final List<MatchStageParameters> matchStageParametersList =
                List.of(new MatchStageParameters("montageMfovAsTilePass1",
                                                 buildFeatureRenderParameters(0.4), // 11 secs for 15 matches between w60_s360_r00_gc_z025_m0017 and w60_s360_r00_gc_z025_m0026
                                                 new FeatureRenderClipParameters(1500, 1500),
                                                 buildFeatureExtractionParameters(),
                                                 buildFeatureMatchDerivation(100),
                                                 buildDisabledGeometricDescriptorAndMatch(),
                                                 null,
                                                 null),
                        new MatchStageParameters("montageMfovAsTilePass2",
                                                 buildFeatureRenderParameters(1.0), // 220 secs for 261 matches between w60_s360_r00_gc_z025_m0017 and w60_s360_r00_gc_z025_m0026
                                                 new FeatureRenderClipParameters(1500, 1500),
                                                 buildFeatureExtractionParameters(),
                                                 buildFeatureMatchDerivation(100),
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
                                                 buildFeatureMatchDerivation(150),
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
        featureMatchDerivation.matchMaxEpsilonFullScale = 10.0f;
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
