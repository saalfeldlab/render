package org.janelia.render.client.multisem;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.multisem.MultiSemUtilities;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;

/**
 * Collection of data needed to support MFOV offset calculations.
 */
public class MFOVOffsetSupportData
        implements Serializable {

    private final StackWithZValues stackWithZ;
    private final Integer minimumNumberOfTilesForIncludedMFOVs;
    private final Map<LayerMFOV, List<TileBounds>> firstLayerMFOVToTileBoundsMap;
    private final Set<String> tileIds;
    private final Map<String, Integer> tileIdToSameLayerPairCountMap;
    private final Map<String, Integer> tileIdToSameLayerMatchCountMap;

    /**
     * @param  stackWithZ                            identifies the stack to process.
     * @param  minimumNumberOfTilesForIncludedMFOVs  only attempt to calculate offsets
     *                                               for MFOVs with this many tiles (or more).
     */
    public MFOVOffsetSupportData(final StackWithZValues stackWithZ,
                                 final Integer minimumNumberOfTilesForIncludedMFOVs) {
        this.stackWithZ = stackWithZ;
        this.minimumNumberOfTilesForIncludedMFOVs = minimumNumberOfTilesForIncludedMFOVs;
        this.firstLayerMFOVToTileBoundsMap = new HashMap<>();
        this.tileIds = new HashSet<>();
        this.tileIdToSameLayerPairCountMap = new HashMap<>();
        this.tileIdToSameLayerMatchCountMap = new HashMap<>();
    }

    /**
     * Build the collections needed to support MFOV offset calculations.
     *
     * @param  renderDataClient  web service client for render stack data.
     * @param  matchDataClient   web service client for match data.
     *
     * @throws IOException
     *   if any error occurs during the build.
     */
    public void buildCollections(final RenderDataClient renderDataClient,
                                 final RenderDataClient matchDataClient)
            throws IOException {

        buildLayerMFOVToTileBoundsMapForFirstZ(renderDataClient);
        buildTileIdToSameLayerPairAndMatchCountMaps(matchDataClient);
    }

    /**
     * @param  maxNumberOfTilesPerMFOV  maximum number of tiles to include in the best connected lists
     *                                  built for each MFOV.
     *
     * @return a map of first layer MFOV identifiers to lists of tile bounds for the best connected tiles in each MFOV.
     */
    public Map<LayerMFOV, List<TileBounds>> buildFirstLayerMfovToBestConnectedTileBoundsMap(final int maxNumberOfTilesPerMFOV) {
        final Map<LayerMFOV, List<TileBounds>> map = new HashMap<>();
        for (final LayerMFOV firstLayerMFOV : firstLayerMFOVToTileBoundsMap.keySet()) {
            map.put(firstLayerMFOV,
                    getTileBoundsWithBestConnections(firstLayerMFOV, maxNumberOfTilesPerMFOV));
        }
        return map;
    }

    /**
     * @return the connection score for the specified tile which is a negative number so that
     *         more connected tiles are sorted ahead of less connected tiles.
     */
    public int getConnectionScoreForTile(final String tileId) {
        final int pairCountWeight = 1000000; // ensure that pair count is more important than match count
        return -1 * ((tileIdToSameLayerPairCountMap.getOrDefault(tileId, 0) * pairCountWeight) +
                     tileIdToSameLayerMatchCountMap.getOrDefault(tileId, 0));
    }

    /**
     * @param  firstLayerMFOV    identifies an MFOV in the first z layer for which tile bounds should be returned.
     * @param  maxNumberOfTiles  maximum number of tiles to include in the returned list.
     *
     * @return a list of tile bounds for the given layer MFOV, sorted by the number of connections
     */
    private List<TileBounds> getTileBoundsWithBestConnections(final LayerMFOV firstLayerMFOV,
                                                              final int maxNumberOfTiles) {
        final List<TileBounds> bestConnectedTileBounds;
        if (firstLayerMFOVToTileBoundsMap.containsKey(firstLayerMFOV)) {

            bestConnectedTileBounds = firstLayerMFOVToTileBoundsMap.get(firstLayerMFOV).stream()
                    // sort by number of pairs * pairCountWeight + number of matches
                    .sorted((tb1, tb2) ->
                                    Integer.compare(getConnectionScoreForTile(tb1.getTileId()),
                                                    getConnectionScoreForTile(tb2.getTileId())))
                    .limit(maxNumberOfTiles)
                    .collect(Collectors.toList());

        } else {
            bestConnectedTileBounds = new ArrayList<>();
        }

        return bestConnectedTileBounds;
    }

    private void buildLayerMFOVToTileBoundsMapForFirstZ(final RenderDataClient renderDataClient)
            throws IOException {

        firstLayerMFOVToTileBoundsMap.clear();

        final StackId renderStackId = stackWithZ.getStackId();
        final String stackName = renderStackId.getStack();
        final Double z = stackWithZ.getzValues().get(0);

        final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stackName, z);
        for (final TileBounds tileBounds : tileBoundsList) {
            final String mfovName = MultiSemUtilities.getMagcMfovForTileId(tileBounds.getTileId());
            final LayerMFOV mfov = new LayerMFOV(z, mfovName);
            final List<TileBounds> mfovTileBoundsList =
                    firstLayerMFOVToTileBoundsMap.computeIfAbsent(mfov,
                                                                  k -> new ArrayList<>());
            mfovTileBoundsList.add(tileBounds);
        }

        if ((minimumNumberOfTilesForIncludedMFOVs != null) && (minimumNumberOfTilesForIncludedMFOVs > 1)) {
            for (final LayerMFOV layerMFOV : firstLayerMFOVToTileBoundsMap.keySet()) {
                final List<TileBounds> mfovTileBoundsList = firstLayerMFOVToTileBoundsMap.get(layerMFOV);
                if (mfovTileBoundsList.size() < minimumNumberOfTilesForIncludedMFOVs) {
                    firstLayerMFOVToTileBoundsMap.remove(layerMFOV);
                }
            }
        }

        for (final LayerMFOV layerMFOV : firstLayerMFOVToTileBoundsMap.keySet()) {
            firstLayerMFOVToTileBoundsMap.get(layerMFOV).forEach(tb -> tileIds.add(tb.getTileId()));
        }
    }

    private void buildTileIdToSameLayerPairAndMatchCountMaps(final RenderDataClient matchDataClient)
            throws IOException {

        for (final Double z : stackWithZ.getzValues()) {

            final String groupId = z.toString();

            final List<CanvasMatches> matchesList = matchDataClient.getMatchesWithinGroup(groupId,
                                                                                          true);

            for (final CanvasMatches canvasMatches : matchesList) {

                final String pTileId = canvasMatches.getpId();
                if (tileIds.contains(pTileId)) {
                    final int priorPairCount = tileIdToSameLayerPairCountMap.getOrDefault(pTileId, 0);
                    tileIdToSameLayerPairCountMap.put(pTileId, priorPairCount + 1);
                    final int priorMatchCount = tileIdToSameLayerMatchCountMap.getOrDefault(pTileId, 0);
                    tileIdToSameLayerMatchCountMap.put(pTileId, priorMatchCount + canvasMatches.getMatchCount());
                }

                final String qTileId = canvasMatches.getqId();
                if (tileIds.contains(qTileId)) {
                    final int priorPairCount = tileIdToSameLayerPairCountMap.getOrDefault(qTileId, 0);
                    tileIdToSameLayerPairCountMap.put(qTileId, priorPairCount + 1);
                    final int priorMatchCount = tileIdToSameLayerMatchCountMap.getOrDefault(qTileId, 0);
                    tileIdToSameLayerMatchCountMap.put(qTileId, priorMatchCount + canvasMatches.getMatchCount());
                }

            }

        }

    }
}
