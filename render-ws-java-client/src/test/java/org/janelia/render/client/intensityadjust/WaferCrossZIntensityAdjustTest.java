package org.janelia.render.client.intensityadjust;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.PreloadedImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.*;

public class WaferCrossZIntensityAdjustTest {

    public static void main(final String[] args) {

        final String baseDataUrl = "http://em-services-1.int.janelia.org:8080/render-ws/v1";
        final String owner = "hess";
        final String project = "wafer_52_cut_00030_to_00039";
        final String alignedStack = "slab_045_all_align_t2_mfov_4_center_19";
        // final String matchCollection = "wafer_52_cut_00030_to_00039_v1"; // TODO: are matches needed?
        // final Double minZ = 1260.0;
        // final Double maxZ = 1285.0;

        if (! new File("/nrs/hess/render/raw").isDirectory()) {
            throw new IllegalStateException("need to map or mount /nrs/hess before running this test");
        }

        final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, project);

        try {
            final List<Double> zValues = dataClient.getStackZValues(alignedStack);
            for (int i = 1; i < zValues.size(); i += 1) {
                final Double pz = zValues.get(i - 1);
                final Double qz = zValues.get(i);
                for (final TileBounds qTileBounds : dataClient.getTileBounds(alignedStack, qz)) {
                    final FilterSpec qFilterSpec = deriveCrossLayerFilterSpec(dataClient,
                                                                              alignedStack,
                                                                              pz,
                                                                              qz,
                                                                              qTileBounds.getTileId(),
                                                                              qTileBounds.toRectangle(),
                                                                              0.25);
                    LOG.info("filter spec for tile {} is {}", qTileBounds.getTileId(), qFilterSpec);
                    break; // TODO: remove short-circuit exit once needs are understood
                }

                break; // TODO: remove short-circuit exit once needs are understood
            }
        } catch (final Throwable t) {
            throw new RuntimeException("caught exception", t);
        }

    }

    private static FilterSpec deriveCrossLayerFilterSpec(final RenderDataClient dataClient,
                                                         final String alignedStack,
                                                         final Double pz,
                                                         final Double qz,
                                                         final String qTileId,
                                                         final Rectangle bounds,
                                                         final Double visualizeRenderScale)
            throws Exception {

        LOG.info("deriveCrossLayerFilterSpec: entry, alignedStack={}, pz={}, qz={}, bounds={}",
                 alignedStack, pz, qz, bounds);

        // make cache large enough to hold tiles and masks for two layers
        final long maxNumberOfCachedPixels = 30_000L * 30_000L;
        final PreloadedImageProcessorCache imageProcessorCache =
                new PreloadedImageProcessorCache(maxNumberOfCachedPixels,
                                                 false,
                                                 false);

        final int numCoefficients = AdjustBlock.DEFAULT_NUM_COEFFICIENTS;

        final String stackUrl = dataClient.getUrls().getStackUrlString(alignedStack);

        final TileSpec firstOverlapSpec = buildLocalOverlapTileSpec(bounds, pz, stackUrl, imageProcessorCache);
        final TileSpec secondOverlapSpec = buildLocalOverlapTileSpec(bounds, qz, stackUrl, imageProcessorCache);

        if (visualizeRenderScale > 0.0) {
            showTileSpec("P original " + qTileId, firstOverlapSpec, visualizeRenderScale, imageProcessorCache);
            showTileSpec("Q original " + qTileId, secondOverlapSpec, visualizeRenderScale, imageProcessorCache);
        }

        final List<MinimalTileSpecWrapper> alignedOverlapBoxes = new ArrayList<>();
        alignedOverlapBoxes.add(new MinimalTileSpecWrapper(firstOverlapSpec));
        alignedOverlapBoxes.add(new MinimalTileSpecWrapper(secondOverlapSpec));

        final ArrayList<OnTheFlyIntensity> corrected =
                AdjustBlock.correctIntensitiesForSliceTiles(alignedOverlapBoxes,
                                                            imageProcessorCache,
                                                            numCoefficients);

        final double[][] pCoefficients = corrected.get(0).getCoefficients();
        final double[][] qCoefficients = corrected.get(1).getCoefficients();
        final double[][] qRelativeToPCoefficients = normalizeCoefficientsForQRelativeToP(pCoefficients,
                                                                                         qCoefficients);

        final TileSpec correctedPSpec = deriveTileSpecWithFilter(firstOverlapSpec,
                                                                 numCoefficients,
                                                                 pCoefficients);
        final TileSpec correctedQSpec = deriveTileSpecWithFilter(secondOverlapSpec,
                                                                 numCoefficients,
                                                                 qCoefficients);
        final TileSpec qRelativeSpec = deriveTileSpecWithFilter(secondOverlapSpec,
                                                                numCoefficients,
                                                                qRelativeToPCoefficients);

        if (visualizeRenderScale > 0.0) {
            showTileSpec("P corrected " + qTileId, correctedPSpec, visualizeRenderScale, imageProcessorCache);
            showTileSpec("Q corrected " + qTileId, correctedQSpec, visualizeRenderScale, imageProcessorCache);
            showTileSpec("Q relative " + qTileId, qRelativeSpec, visualizeRenderScale, imageProcessorCache);
        }

        return qRelativeSpec.getFilterSpec();
    }

    private static final Logger LOG = LoggerFactory.getLogger(WaferCrossZIntensityAdjustTest.class);

}
