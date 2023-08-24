package org.janelia.render.client.intensityadjust;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.NeuroglancerUtil;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.janelia.render.client.parameter.IntensityAdjustParameters;
import org.janelia.render.client.parameter.IntensityAdjustParameters.StrategyName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core logic for distributed intensity correction processing that can be used for either LSF Array or Spark jobs.
 */
public class IntensityCorrectionWorker implements Serializable {

    private final IntensityAdjustParameters parameters;
    private final List<Double> zValues;
    private final StackMetaData stackMetaData;
    private final String slicePathFormatSpec;
    private final IntensityCorrectionStrategy strategy;

    public IntensityCorrectionWorker(final IntensityAdjustParameters parameters,
                                     final RenderDataClient dataClient) throws IOException {
        this.parameters = parameters;

        this.zValues = dataClient.getStackZValues(parameters.algorithmic.stack,
                                                  parameters.layerRange.minZ,
                                                  parameters.layerRange.maxZ,
                                                  parameters.zValues);
        if (this.zValues.isEmpty()) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        if (StrategyName.AFFINE.equals(parameters.strategyName)) {

            this.strategy = new AffineIntensityCorrectionStrategy(parameters.algorithmic.lambda1,
                                                                  parameters.algorithmic.lambda2);

        } else if (StrategyName.FIRST_LAYER_QUADRATIC.equals(parameters.strategyName)) {

            this.strategy = new QuadraticIntensityCorrectionStrategy(parameters.algorithmic.lambda1,
                                                                     parameters.algorithmic.lambda2,
                                                                     this.zValues.get(0));

        } else if (StrategyName.ALL_LAYERS_QUADRATIC.equals(parameters.strategyName)) {

            this.strategy = new QuadraticIntensityCorrectionStrategy(parameters.algorithmic.lambda1,
                                                                     parameters.algorithmic.lambda2,
                                                                     new HashSet<>(this.zValues));

        } else {
            throw new IllegalArgumentException(parameters.strategyName + " strategy is not supported");
        }

        this.stackMetaData = dataClient.getStackMetaData(parameters.algorithmic.stack);
        if (parameters.intensityCorrectedFilterStack == null) {
            final File sectionRootDirectory = parameters.getSectionRootDirectory(new Date());
            FileUtil.ensureWritableDirectory(sectionRootDirectory);
            this.slicePathFormatSpec = parameters.getSlicePathFormatSpec(stackMetaData,
                                                                         sectionRootDirectory);
        } else {
            this.slicePathFormatSpec = null;
        }

        if (parameters.deriveFilterData()) {
            dataClient.setupDerivedStack(stackMetaData, parameters.intensityCorrectedFilterStack);
        }
    }

    public List<Double> getzValues() {
        return zValues;
    }

    public void correctZRange(final RenderDataClient dataClient,
                              final Double minZ,
                              final Double maxZ)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("correctZRange: entry, minZ={}, maxZ={}", minZ, maxZ);
        
        final ResolvedTileSpecCollection resolvedTiles;
        if (minZ.equals(maxZ)) {
            resolvedTiles = dataClient.getResolvedTiles(parameters.algorithmic.stack, minZ);
        } else {
            resolvedTiles = dataClient.getResolvedTilesForZRange(parameters.algorithmic.stack, minZ, maxZ);
        }

        if (parameters.deriveFilterData()) {
            deriveAndStoreIntensityFilterData(dataClient,
                                              resolvedTiles);
        } else {
            for (int z = minZ.intValue(); z <= maxZ.intValue(); z += 1) {
                renderIntensityAdjustedScape(dataClient,
                                             resolvedTiles,
                                             z);
            }
        }

        LOG.info("correctZRange: exit, minZ={}, maxZ={}", minZ, maxZ);
    }

    public void deriveAndStoreIntensityFilterData(final RenderDataClient dataClient,
                                                  final ResolvedTileSpecCollection resolvedTiles)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("deriveAndStoreIntensityFilterData: entry");

        if (resolvedTiles.getTileCount() > 1) {
            final long maxCachedPixels = parameters.getMaxNumberOfCachedPixels();
            final ImageProcessorCache imageProcessorCache =
                    maxCachedPixels == 0 ?
                    ImageProcessorCache.DISABLED_CACHE :
                    new ImageProcessorCache(parameters.getMaxNumberOfCachedPixels(),
                                            true,
                                            false);

            final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);

            final List<OnTheFlyIntensity> corrected =
                    AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                                parameters.algorithmic.renderScale,
                                                                parameters.algorithmic.zDistance,
                                                                imageProcessorCache,
                                                                parameters.algorithmic.numCoefficients,
                                                                strategy,
                                                                parameters.numThreads);

            for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
                final String tileId = onTheFlyIntensity.getMinimalTileSpecWrapper().getTileId();
                final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                final IntensityMap8BitFilter filter = onTheFlyIntensity.toFilter();
                final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(),
                                                             filter.toParametersMap());
                tileSpec.setFilterSpec(filterSpec);
                tileSpec.convertSingleChannelSpecToLegacyForm();
            }
        } else {
            final String tileCountMsg = resolvedTiles.getTileCount() == 1 ? "1 tile" : "0 tiles";
            LOG.info("deriveAndStoreIntensityFilterData: skipping correction because collection contains {}",
                     tileCountMsg);
        }

        dataClient.saveResolvedTiles(resolvedTiles,
                                     parameters.intensityCorrectedFilterStack,
                                     null);
    }

    public void renderIntensityAdjustedScape(final RenderDataClient dataClient,
                                             final ResolvedTileSpecCollection resolvedTiles,
                                             final int integralZ)
            throws ExecutionException, InterruptedException, IOException {

        LOG.info("renderIntensityAdjustedScape: entry, integralZ={}", integralZ);

        final Bounds stackBounds = stackMetaData.getStats().getStackBounds();

        final String parametersUrl =
                dataClient.getRenderParametersUrlString(parameters.algorithmic.stack,
                                                        stackBounds.getMinX(),
                                                        stackBounds.getMinY(),
                                                        integralZ,
                                                        (int) (stackBounds.getDeltaX() + 0.5),
                                                        (int) (stackBounds.getDeltaY() + 0.5),
                                                        1.0,
                                                        null);

        final RenderParameters sliceRenderParameters = RenderParameters.loadFromUrl(parametersUrl);

        final ImageProcessorCache imageProcessorCache =
                new ImageProcessorCache(parameters.getMaxNumberOfCachedPixels(),
                                        true,
                                        false);

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks slice;
//        switch (correctionMethod) {
//            case GAUSS:
//            case GAUSS_WEIGHTED:
//                slice = AdjustBlock.renderIntensityAdjustedSliceGauss(stack,
//                                                                      dataClient,
//                                                                      interval,
//                                                                      CorrectionMethod.GAUSS_WEIGHTED.equals(correctionMethod),
//                                                                      false,
//                                                                      integralZ);
//
//                break;
//            case GLOBAL_PER_SLICE:
        slice = AdjustBlock.renderIntensityAdjustedSliceGlobalPerSlice(resolvedTiles,
                                                                       sliceRenderParameters,
                                                                       parameters.algorithmic.renderScale,
                                                                       parameters.algorithmic.zDistance,
                                                                       imageProcessorCache,
                                                                       integralZ,
                                                                       AdjustBlock.DEFAULT_NUM_COEFFICIENTS,
                                                                       strategy,
                                                                       parameters.numThreads);
//                break;
//            default:
//                throw new UnsupportedOperationException("only support GLOBAL_PER_SLICE for hack");
//                slice = AdjustBlock.renderIntensityAdjustedSlice(stack,
//                                                                 dataClient,
//                                                                 interval,
//                                                                 1.0,
//                                                                 false,
//                                                                 integralZ);
//                break;
//        }

        final BufferedImage sliceImage = slice.ip.getBufferedImage();

        final String slicePath = String.format(slicePathFormatSpec, integralZ);

        Utils.saveImage(sliceImage, slicePath, parameters.format, false, 0.85f);
    }

    public void completeCorrectedStackAsNeeded(final RenderDataClient dataClient)
            throws IOException {
        if (parameters.deriveFilterData() && parameters.completeCorrectedStack) {
            dataClient.setStackState(parameters.intensityCorrectedFilterStack,
                                     StackMetaData.StackState.COMPLETE);

            final StackMetaData stackMetaData = dataClient.getStackMetaData(parameters.intensityCorrectedFilterStack);
            final String ngUrl = NeuroglancerUtil.buildRenderStackUrlString("http://renderer.int.janelia.org:8080",
                                                                            stackMetaData);
            LOG.info("Neuroglancer URL for intensity corrected stack is: {}", ngUrl);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(IntensityCorrectionWorker.class);
}
