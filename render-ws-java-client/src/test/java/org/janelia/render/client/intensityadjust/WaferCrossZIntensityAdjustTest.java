package org.janelia.render.client.intensityadjust;

import java.io.File;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.filter.Filter;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.filter.QuadraticIntensityMap8BitFilter;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.PreloadedImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;

import ij.ImageJ;
import ij.ImagePlus;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.buildLocalOverlapTileSpec;
import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.showTileSpec;

public class WaferCrossZIntensityAdjustTest {

    protected static final double visualizeRenderScale = 0.2;

    public static void main(final String[] args) {

        final String baseDataUrl = "http://em-services-1.int.janelia.org:8080/render-ws/v1";
        final String owner = "hess";
        final String project = "wafer_52_cut_00030_to_00039";
        final String alignedStack = "slab_045_all_align_t2_mfov_4_center_19";
        final Double minZ = 1260.0;
        final Double maxZ = 1261.0;

        final boolean onlyShowOriginal = false;

//        final String tileNumber = "_014_";
        final String tileNumber = "_001_";

        if (! new File("/nrs/hess/render/raw").isDirectory()) {
            throw new IllegalStateException("need to map or mount /nrs/hess before running this test");
        }

        final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, project);
        final PreloadedImageProcessorCache imageProcessorCache =
                new PreloadedImageProcessorCache(15_000L * 15_000L, false, false);

        try {
            final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTilesForZRange(alignedStack, minZ, maxZ);
            final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);

            List<OnTheFlyIntensity> corrected = null;
            if (! onlyShowOriginal) {
                final int numThreads = 12;

//                final IntensityCorrectionStrategy strategy = new AffineIntensityCorrectionStrategy();
                final IntensityCorrectionStrategy strategy = new QuadraticIntensityCorrectionStrategy(0.01, 0.01, minZ);

                corrected = AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                                        imageProcessorCache,
                                                                        AdjustBlock.DEFAULT_NUM_COEFFICIENTS,
                                                                        strategy,
                                                                        numThreads);
            }

            new ImageJ();

            final TileBounds xyBounds = dataClient.getTileBounds(alignedStack, minZ).stream().filter(tile -> tile.getTileId().contains(tileNumber)).findFirst().orElseThrow();
            final String stackUrl = dataClient.getUrls().getStackUrlString(alignedStack);

            for (int z = minZ.intValue(); z <= maxZ.intValue(); ++z) {

                final TileSpec tileSpec = buildLocalOverlapTileSpec(xyBounds.toRectangle(), z, stackUrl, imageProcessorCache);
                final String tileId = tileSpec.getTileId();
                showTileSpec("original " + tileId, tileSpec, visualizeRenderScale, imageProcessorCache);

                if(!onlyShowOriginal) {
                    final OnTheFlyIntensity correctedTile =
                            corrected.stream()
                                    .filter(otfi -> otfi.getMinimalTileSpecWrapper().getTileId().contains(tileNumber))
                                    .findFirst()
                                    .orElseThrow();

                    final double[][] coefficients = correctedTile.getCoefficients();

                    final TileSpec correctedTileSpec = deriveTileSpecWithFilter(tileSpec,
                                                                                AdjustBlock.DEFAULT_NUM_COEFFICIENTS,
                                                                                coefficients);

                    showTileSpec("corrected " + tileId, correctedTileSpec, visualizeRenderScale, imageProcessorCache);
                }
            }
        } catch (final Throwable t) {
            throw new RuntimeException("caught exception", t);
        }
    }

    protected static void showTileSpecOnCanvas(final String title, final TileSpec tileSpec, final ImageProcessorCache ipc, final Bounds canvas) {

        final RenderParameters parameters = new RenderParameters(
                null,canvas.getX(), canvas.getY(), canvas.getWidth(), canvas.getHeight(), visualizeRenderScale);
        parameters.addTileSpec(tileSpec);
        parameters.initializeDerivedValues();

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm = Renderer.renderImageProcessorWithMasks(parameters, ipc);
        new ImagePlus(title, ipwm.ip.convertToByteProcessor()).show();
    }

    public static TileSpec deriveTileSpecWithFilter(final TileSpec tileSpec,
            final int numCoefficients,
            final double[][] coefficients) {
        final TileSpec derivedTileSpec = tileSpec.slowClone();
        final int numPolynomialCoefficients = (coefficients == null) ? 0 : coefficients[0].length;
        final Filter filter;

        switch (numPolynomialCoefficients) {
            case 2:
                filter = new LinearIntensityMap8BitFilter(numCoefficients, numCoefficients, 2, coefficients);
                break;

            case 3:
                filter = new QuadraticIntensityMap8BitFilter(numCoefficients, numCoefficients, 3, coefficients);
                break;

            default:
                throw new IllegalArgumentException("Cannot instantiate 8bit intensity filter for " + numPolynomialCoefficients + " coefficients.");
        }

        final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(), filter.toParametersMap());
        derivedTileSpec.setFilterSpec(filterSpec);
        derivedTileSpec.convertSingleChannelSpecToLegacyForm();
        return derivedTileSpec;
    }
}
