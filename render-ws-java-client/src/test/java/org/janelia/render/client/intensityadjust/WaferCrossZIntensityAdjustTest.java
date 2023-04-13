package org.janelia.render.client.intensityadjust;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.filter.LinearIntensityMap8BitFilter;
import org.janelia.alignment.filter.QuadraticIntensityMap8BitFilter;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;

import ij.ImageJ;
import ij.ImagePlus;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensityQuadratic;

import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.deriveTileSpecWithFilter;

public class WaferCrossZIntensityAdjustTest {

    protected static final double visualizeRenderScale = 0.1;

    public static void main(final String[] args) {

        final String baseDataUrl = "http://em-services-1.int.janelia.org:8080/render-ws/v1";
        final String owner = "hess";
        final String project = "wafer_52_cut_00030_to_00039";
        final String alignedStack = "slab_045_all_align_t2_mfov_4_center_19";
        final Double minZ = 1260.0;
        final Double maxZ = 1261.0;

        final boolean onlyShowOriginal = false;

        final String[] tileIdsToVisualize = {
                "045_000004_014_20220401_183940.1260.0",
                "045_000004_014_20220401_221256.1261.0"
//                "045_000004_014_20220402_160252.1262.0",
//                "045_000004_014_20220402_211657.1263.0"
        };

        if (! new File("/nrs/hess/render/raw").isDirectory()) {
            throw new IllegalStateException("need to map or mount /nrs/hess before running this test");
        }

        final RenderDataClient dataClient = new RenderDataClient(baseDataUrl, owner, project);
        final ImageProcessorCache imageProcessorCache =
                new ImageProcessorCache(15_000L * 15_000L,
                                        false,
                                        false);

        try {
            final ResolvedTileSpecCollection resolvedTiles = dataClient.getResolvedTilesForZRange(alignedStack,
                                                                                                  minZ,
                                                                                                  maxZ);


            final List<MinimalTileSpecWrapper> wrappedTiles = AdjustBlock.wrapTileSpecs(resolvedTiles);
            final ArrayList<OnTheFlyIntensityQuadratic> corrected = onlyShowOriginal ? null :
                    AdjustBlock.correctIntensitiesForSliceTilesQuadratic(wrappedTiles,
                                                                imageProcessorCache,
                                                                AdjustBlock.DEFAULT_NUM_COEFFICIENTS);

            new ImageJ();

            Bounds canvas = null;
            for (final String tileId : tileIdsToVisualize) {
                final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                canvas = (canvas == null) ? tileSpec.toTileBounds() : canvas.union(tileSpec.toTileBounds());
            }

            for (final String tileId : tileIdsToVisualize) {

                if (onlyShowOriginal) {
                    final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                    showTileSpecOnCanvas("original " + tileId, tileSpec, imageProcessorCache, canvas);
                } else {
                    final OnTheFlyIntensityQuadratic correctedTile =
                            corrected.stream()
                                    .filter(otfi -> otfi.getMinimalTileSpecWrapper().getTileId().equals(tileId))
                                    .findFirst()
                                    .orElseThrow();

                    final TileSpec tileSpec = correctedTile.getMinimalTileSpecWrapper().getTileSpec();
                    showTileSpecOnCanvas("original " + tileId, tileSpec, imageProcessorCache, canvas);

                    final double[][] coefficients = correctedTile.getCoefficients();
                    final TileSpec correctedTileSpec = deriveTileSpecWithFilterQuadratic(tileSpec,
                                                                                         AdjustBlock.DEFAULT_NUM_COEFFICIENTS,
                                                                                         coefficients);
                    showTileSpecOnCanvas("corrected " + tileId, correctedTileSpec, imageProcessorCache, canvas);
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

    public static TileSpec deriveTileSpecWithFilterQuadratic(final TileSpec tileSpec,
                                                             final int numCoefficients,
                                                             final double[][] coefficients) {
        final TileSpec derivedTileSpec = tileSpec.slowClone();
        final QuadraticIntensityMap8BitFilter filter =
                new QuadraticIntensityMap8BitFilter(numCoefficients, numCoefficients, 3, coefficients);
        final FilterSpec filterSpec = new FilterSpec(filter.getClass().getName(),
                                                     filter.toParametersMap());
        derivedTileSpec.setFilterSpec(filterSpec);
        derivedTileSpec.convertSingleChannelSpecToLegacyForm();
        return derivedTileSpec;
    }
}
