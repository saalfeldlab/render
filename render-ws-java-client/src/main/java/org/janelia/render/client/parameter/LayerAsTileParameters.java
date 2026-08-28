package org.janelia.render.client.parameter;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.match.MatchFilter;
import org.janelia.alignment.match.ModelType;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
import org.janelia.alignment.match.parameters.MatchRunParameters;
import org.janelia.alignment.match.parameters.MatchStageParameters;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;

/**
 * Parameters for building layer-as-tile images, matches, and stacks.
 */
public class LayerAsTileParameters
        implements Serializable {

    private final Double layerRenderScale;
    private final String layerRootDirectory;
    private final String dynamicLayerStackSuffix;
    private final String renderedLayerStackSuffix;
    private String renderedLayerRunTimestamp;
    private final String alignedLayerStackSuffix;
    private final String align3DSfovStackSuffix;

    public LayerAsTileParameters() {
        this(null,
             null,
             null,
             null,
             null,
             null);
    }

    public LayerAsTileParameters(final Double layerRenderScale,
                                 final String layerRootDirectory,
                                 final String dynamicLayerStackSuffix,
                                 final String renderedLayerStackSuffix,
                                 final String alignedLayerStackSuffix,
                                 final String align3DSfovStackSuffix) {
        this(layerRenderScale,
             layerRootDirectory,
             dynamicLayerStackSuffix,
             renderedLayerStackSuffix,
             null,
             alignedLayerStackSuffix,
             align3DSfovStackSuffix);
    }

    public LayerAsTileParameters(final Double layerRenderScale,
                                 final String layerRootDirectory,
                                 final String dynamicLayerStackSuffix,
                                 final String renderedLayerStackSuffix,
                                 final String renderedLayerRunTimestamp,
                                 final String alignedLayerStackSuffix,
                                 final String align3DSfovStackSuffix) {
        this.layerRenderScale = layerRenderScale;
        this.layerRootDirectory = layerRootDirectory;
        this.dynamicLayerStackSuffix = dynamicLayerStackSuffix;
        this.renderedLayerStackSuffix = renderedLayerStackSuffix;
        this.renderedLayerRunTimestamp = renderedLayerRunTimestamp;
        this.alignedLayerStackSuffix = alignedLayerStackSuffix;
        this.align3DSfovStackSuffix = align3DSfovStackSuffix;
    }

    public Double getLayerRenderScale() {
        return layerRenderScale;
    }

    public String getLayerRootDirectory() {
        return layerRootDirectory;
    }

    public String getDynamicLayerStackSuffix() {
        return dynamicLayerStackSuffix;
    }

    public String getRenderedLayerStackSuffix() {
        return renderedLayerStackSuffix;
    }

    public String getAlignedLayerStackSuffix() {
        return alignedLayerStackSuffix;
    }

    public String getAlign3DSfovStackSuffix() {
        return align3DSfovStackSuffix;
    }

    public String getDynamicLayerStackSuffixForRawSfovStack() {
        return dynamicLayerStackSuffix;
    }

    public String getRenderedLayerStackSuffixForRawSfovStack() {
        return getDynamicLayerStackSuffixForRawSfovStack() + renderedLayerStackSuffix;
    }

    public String getAlignedLayerStackSuffixForRawSfovStack() {
        return getRenderedLayerStackSuffixForRawSfovStack() + alignedLayerStackSuffix;
    }

    public StackId getDynamicLayerStackId(final StackId rawSfovStackId) {
        return rawSfovStackId.withStackSuffix(getDynamicLayerStackSuffixForRawSfovStack());
    }

    public StackId getRenderedLayerStackId(final StackId rawSfovStackId) {
        return rawSfovStackId.withStackSuffix(getRenderedLayerStackSuffixForRawSfovStack());
    }

    public StackId getAlign3DSfovStackId(final StackId rawSfovStackId) {
        return rawSfovStackId.withStackSuffix(align3DSfovStackSuffix);
    }

    public String getRenderedLayerRunTimestamp() {
        if (this.renderedLayerRunTimestamp == null) {
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
            this.renderedLayerRunTimestamp = sdf.format(new Date());
        }
        return this.renderedLayerRunTimestamp;
    }

    public List<MatchRunParameters> buildLayerMatchRunList(final Bounds renderedLayerStackBounds) {
        final List<MatchRunParameters> layerMatchRunList = new ArrayList<>();
        layerMatchRunList.add(buildCrossMatchRunParameters(renderedLayerStackBounds));
        return layerMatchRunList;
    }

    /**
     * @return the minimum number of match inliers to require for layers with the specified bounds.
     *         Larger layers need more inliers, so the count is derived from the layer area
     *         instead of being hard-coded for layers of any size.
     */
    public static int deriveMatchMinNumInliers(final Bounds renderedLayerStackBounds) {
        final double layerPixelCount = renderedLayerStackBounds.getDeltaX() * renderedLayerStackBounds.getDeltaY();
        return (int) Math.round(layerPixelCount / PIXELS_PER_MIN_INLIER);
    }

    public AffineBlockSolverSetup buildLayerAffineBlockSolverSetup() {

        final AffineBlockSolverSetup setup = new AffineBlockSolverSetup();

        setup.preAlign = FIBSEMAlignmentParameters.PreAlign.NONE;

        setup.distributedSolve.maxAllowedErrorGlobal = 10.0;
        setup.distributedSolve.maxIterationsGlobal = 1000;
        setup.distributedSolve.maxPlateauWidthGlobal = 200;
        setup.distributedSolve.threadsWorker = 1;
        setup.distributedSolve.threadsGlobal = 1;
        setup.distributedSolve.deriveThreadsUsingSparkConfig = true;

        setup.targetStack.stackSuffix = this.alignedLayerStackSuffix;
        setup.targetStack.completeStack = true;

        setup.blockPartition.sizeZ = 100; // must be greater than total number of layers in each layer-as-tile stack

        setup.stitching.lambda = 0.0;
        setup.stitching.maxAllowedError = 10.0;
        setup.stitching.maxIterations = 5000;
        setup.stitching.maxPlateauWidth = 1000;
        setup.stitching.minInliers = 25;

        setup.blockOptimizer.lambdasRigid = List.of(1.0, 1.0, 0.9, 0.3, 0.01);
        // NOTE: lambda's translation means how much do you want to regularize with a translation model, thus 1.0 means 100% translation
        setup.blockOptimizer.lambdasTranslation = MFOVAsTileParameters.SolveType.AFFINE.getLambdasTranslation();
        setup.blockOptimizer.lambdasRegularization = List.of(0.0, 0.0, 0.0, 0.0, 0.0);

        setup.blockOptimizer.iterations = List.of(1000, 1000, 500, 250, 250);
        setup.blockOptimizer.maxPlateauWidth = List.of(250, 250, 150, 100, 100);
        setup.blockOptimizer.maxAllowedError = 10.0;

        setup.maxNumMatches = 0;

        setup.alternatingRuns.nRuns = 1;
        setup.alternatingRuns.keepIntermediateStacks = false;

        return setup;
    }

    private static MatchRunParameters buildCrossMatchRunParameters(final Bounds renderedLayerStackBounds) {

        // renderWithFilter = true greatly improves results when no intensity correction has been run on SFOVs
        final boolean renderWithFilter = true;

        final int matchMinNumInliers = deriveMatchMinNumInliers(renderedLayerStackBounds);

        final List<MatchStageParameters> matchStageParametersList =
                List.of(
                        // pass 1 render scale 0.5 with AGGREGATED_CONSENSUS_SETS
                        new MatchStageParameters("crossLayerAsTilePass1",
                                                 MFOVAsTileParameters.buildFeatureRenderParameters(0.5,
                                                                                                   renderWithFilter),
                                                 new FeatureRenderClipParameters(),
                                                 MFOVAsTileParameters.buildFeatureExtractionParameters(),
                                                 MFOVAsTileParameters.buildFeatureMatchDerivation(MatchFilter.FilterType.AGGREGATED_CONSENSUS_SETS,
                                                                                                  matchMinNumInliers,
                                                                                                  ModelType.RIGID),
                                                 MFOVAsTileParameters.buildDisabledGeometricDescriptorAndMatch(),
                                                 null,
                                                 null));
        // zNeighborDistance = 3 improves results (over zNeighborDistance = 1)
        final int zNeighborDistance = 3;
        return new MatchRunParameters("crossLayerAsTileRun",
                                      MFOVAsTileParameters.buildMatchCommonParameters(2),
                                      MFOVAsTileParameters.buildTilePairDerivationParameters(0.1,
                                                                                             zNeighborDistance,
                                                                                             true),
                                      matchStageParametersList);
    }

    /**
     * Number of layer pixels for each required match inlier.
     * Layers with 12 million pixels typically need a minimum of 100 inliers.
     */
    private static final double PIXELS_PER_MIN_INLIER = 120_000.0;
}
