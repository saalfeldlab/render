package org.janelia.render.client.solver;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prototype/test code for Preibisch ...
 */
@SuppressWarnings("SameParameterValue")
public class DebugTileDataTest {

    public static void main(final String[] args)
            throws Exception {

        final String baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        final String owner = "hess_wafer_53";
        final String project = "cut_000_to_009";
        final String stack = "c000_s095_v01";

        final RenderDataClient renderDataClient = new RenderDataClient(baseDataUrl,
                                                                       owner,
                                                                       project);

        final TileBounds firstTileBounds = fetchTileData(renderDataClient, stack);

        // Shows how to use render parameters built from web service query to find tiles at location.
        // This is good for a small number of locations and provides immediate access to full tile specs.
        findTilesWithinBoxUsingRenderParameters(renderDataClient,
                                                stack,
                                                firstTileBounds.getMaxX() - 50,
                                                firstTileBounds.getMaxY() - 50,
                                                100,
                                                100,
                                                firstTileBounds.getZ());

        // Shows how to use tile bounds from web service with local in-memory RTree to find tiles at location.
        // This is good for querying many locations because search is done client-side.
        // If you want full tile spec data, you need to pull it separately and then map from tileId in bounds object.
        findTilesWithinBoxUsingRTree(renderDataClient,
                                     stack,
                                     firstTileBounds.getMaxX() - 50,
                                     firstTileBounds.getMaxY() - 50,
                                     100,
                                     100,
                                     firstTileBounds.getZ());
    }

    private static TileBounds fetchTileData(final RenderDataClient renderDataClient,
                                            final String stack)
            throws IOException {

        final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
        LOG.info("stackMetaData is {}", stackMetaData);

        final List<Double> zValues = renderDataClient.getStackZValues(stack);

        final Map<Double, List<TileBounds>> zToTileBoundsLists = new HashMap<>();
        final Map<Double, List<TileSpec>> zToTileSpecLists = new HashMap<>();

        for (final Double z : zValues) {
            // faster lightweight query for just the tile bounds instead of the entire tileSpec (if you only need bounds)
            final List<TileBounds> tileBoundsForZ = renderDataClient.getTileBounds(stack, z);
            zToTileBoundsLists.put(z, tileBoundsForZ);

            // still fast, but heavier query for tile specs that include transforms
            final ResolvedTileSpecCollection resolvedTiles = renderDataClient.getResolvedTiles(stack, z);
            final List<TileSpec> tileSpecsForZSortedByTileId =
                    resolvedTiles.getTileSpecs().stream()
                            .sorted(Comparator.comparing(TileSpec::getTileId))
                            .collect(Collectors.toList());
            zToTileSpecLists.put(z, tileSpecsForZSortedByTileId);
        }

        final Double firstZ = zValues.get(0);
        final List<TileBounds> tileBoundsForFirstZ = zToTileBoundsLists.get(firstZ);
        final TileBounds firstTileBounds = tileBoundsForFirstZ.get(0);
        LOG.info("first tile bounds are {}", firstTileBounds);

        final List<TileSpec> tileSpecsForFirstZ = zToTileSpecLists.get(firstZ);
        final TileSpec firstTileSpec = tileSpecsForFirstZ.get(0);
        LOG.info("first tile spec is {}", firstTileSpec.toJson());

        return firstTileBounds;
    }

    private static void findTilesWithinBoxUsingRenderParameters(final RenderDataClient renderDataClient,
                                                                final String stack,
                                                                final double x,
                                                                final double y,
                                                                final int width,
                                                                final int height,
                                                                final double z) {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String boxUrlString = urls.getRenderParametersUrlString(stack, x, y, z, width, height, 1.0, null);

        final RenderParameters boxRenderParameters = RenderParameters.loadFromUrl(boxUrlString);
        final List<TileSpec> tileSpecsInBox = boxRenderParameters.getTileSpecs();

        for (final TileSpec tileSpec : tileSpecsInBox) {
            LOG.info("tile {} is within box", tileSpec.getTileId());
        }
    }

    private static void findTilesWithinBoxUsingRTree(final RenderDataClient renderDataClient,
                                                     final String stack,
                                                     final double x,
                                                     final double y,
                                                     final int width,
                                                     final int height,
                                                     final double z)
            throws IOException {

        final List<TileBounds> tileBoundsForZ = renderDataClient.getTileBounds(stack, z);
        final TileBoundsRTree tileBoundsRTree = new TileBoundsRTree(z, tileBoundsForZ);
        final List<TileBounds> tilesInBox = tileBoundsRTree.findTilesInBox(x, y, x + width, y + height);

        for (final TileBounds tileBounds : tilesInBox) {
            LOG.info("tile {} is within box", tileBounds.getTileId());
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DebugTileDataTest.class);
}
