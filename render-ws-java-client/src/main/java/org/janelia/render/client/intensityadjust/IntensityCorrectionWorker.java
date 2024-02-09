package org.janelia.render.client.intensityadjust;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.IntensityMap8BitFilter;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
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

        final StackMetaData stackMetaData = dataClient.getStackMetaData(parameters.algorithmic.stack);
        dataClient.setupDerivedStack(stackMetaData, parameters.intensityCorrectedFilterStack);
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

        deriveAndStoreIntensityFilterData(dataClient, resolvedTiles);

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

            final List<TileSpec> wrappedTiles = AdjustBlock.sortTileSpecs(resolvedTiles);

            // TODO: make this also work with selective z-layers
            final int maxZDistance = parameters.algorithmic.zDistance.getMaxZDistance();
            final List<OnTheFlyIntensity> corrected =
                    AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                                parameters.algorithmic.renderScale,
                                                                maxZDistance,
                                                                imageProcessorCache,
                                                                parameters.algorithmic.numCoefficients,
                                                                strategy,
                                                                parameters.numThreads);

            for (final OnTheFlyIntensity onTheFlyIntensity : corrected) {
                final String tileId = onTheFlyIntensity.getTileSpec().getTileId();
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

    public void completeCorrectedStackAsNeeded(final RenderDataClient dataClient)
            throws IOException {
        if (parameters.completeCorrectedStack) {
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
