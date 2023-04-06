package org.janelia.render.client.intensityadjust;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.intensityadjust.virtual.OnTheFlyIntensity;

import ij.ImageJ;

import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.deriveTileSpecWithFilter;
import static org.janelia.render.client.intensityadjust.OcellarCrossZIntensityCorrection.showTileSpec;

public class WaferCrossZIntensityAdjustTest {

    public static void main(final String[] args) {

        final String baseDataUrl = "http://em-services-1.int.janelia.org:8080/render-ws/v1";
        final String owner = "hess";
        final String project = "wafer_52_cut_00030_to_00039";
        final String alignedStack = "slab_045_all_align_t2_mfov_4_center_19";
        final Double minZ = 1260.0;
        final Double maxZ = 1263.0;

        final boolean onlyShowOriginal = true;

        final double visualizeRenderScale = 0.1;
        final String[] tileIdsToVisualize = {
                "045_000004_014_20220401_183940.1260.0",// "045_000004_002_20220401_183940.1260.0",
                "045_000004_014_20220401_221256.1261.0",// "045_000004_002_20220401_221256.1261.0"
                "045_000004_014_20220402_160252.1262.0",
                "045_000004_014_20220402_211657.1263.0"
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
            final ArrayList<OnTheFlyIntensity> corrected = onlyShowOriginal ? null :
                    AdjustBlock.correctIntensitiesForSliceTiles(wrappedTiles,
                                                                imageProcessorCache,
                                                                AdjustBlock.DEFAULT_NUM_COEFFICIENTS);

            new ImageJ();

            for (final String tileId : tileIdsToVisualize) {

            	if (onlyShowOriginal)
            	{
            		final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);

	                showTileSpec("original " + tileId,
                            tileSpec,
                            visualizeRenderScale,
                            imageProcessorCache);
            	}
            	else
            	{
	                final OnTheFlyIntensity correctedTile =
	                        corrected.stream()
	                                .filter(otfi -> otfi.getMinimalTileSpecWrapper().getTileId().equals(tileId))
	                                .findFirst()
	                                .orElseThrow();
	                final TileSpec tileSpec = correctedTile.getMinimalTileSpecWrapper().getTileSpec();
	                final double[][] coefficients = correctedTile.getCoefficients();
	
	                final TileSpec correctedTileSpec = deriveTileSpecWithFilter(tileSpec,
	                                                                            AdjustBlock.DEFAULT_NUM_COEFFICIENTS,
	                                                                            coefficients);
	
	                showTileSpec("original " + tileId,
	                             tileSpec,
	                             visualizeRenderScale,
	                             imageProcessorCache);
	                showTileSpec("corrected " + tileId,
	                             correctedTileSpec,
	                             visualizeRenderScale,
	                             imageProcessorCache);
            	}
            }
        } catch (final Throwable t) {
            throw new RuntimeException("caught exception", t);
        }

    }

}
