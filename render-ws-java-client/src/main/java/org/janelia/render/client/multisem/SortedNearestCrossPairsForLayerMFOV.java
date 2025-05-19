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

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.multisem.LayerMFOV;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.MFOVOffsetParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility to build a list of potential cross layer tile pairs for a layer MFOV.
 */
public class SortedNearestCrossPairsForLayerMFOV
        implements Serializable {

    private final StackId stackId;
    private final LayerMFOV layerMFOV;
    private final List<TileBounds> sortedLayerTileBoundsList;
    private final Map<String, List<OrderedCanvasIdPair>> tileIdToSortedNearestPairsList;

    /**
     * @param  stackId                    identifies the stack.
     * @param  layerMFOV                  identifies the current layer MFOV.
     * @param  sortedLayerTileBoundsList  bounds of current layer tiles for which nearest next layer
     *                                    tiles are to be found and tracked as potential overlapping tiles.
     * @param  nextLayerTileBoundsRTree   all tile bounds for the next layer.
     * @param  mfovOffsetParameters       parameters for building MFOV offset stacks.
     */
    public SortedNearestCrossPairsForLayerMFOV(final StackId stackId,
                                               final LayerMFOV layerMFOV,
                                               final List<TileBounds> sortedLayerTileBoundsList,
                                               final TileBoundsRTree nextLayerTileBoundsRTree,
                                               final MFOVOffsetParameters mfovOffsetParameters) {
        this.stackId = stackId;
        this.layerMFOV = layerMFOV;
        this.sortedLayerTileBoundsList = sortedLayerTileBoundsList;
        this.tileIdToSortedNearestPairsList = new HashMap<>();

        LOG.info("constructor: entry, stack={}, layerMFOV={}, sortedLayerTileBoundsList.size={}",
                 stackId.toDevString(), layerMFOV, sortedLayerTileBoundsList.size());

        if (LOG.isDebugEnabled()) {
            for (final TileBounds currentTileBounds : sortedLayerTileBoundsList) {
                LOG.debug("constructor: entry, currentTileBounds is {}", currentTileBounds);
            }
        }

        // keep track of all pairs created to avoid duplicates
        final Set<OrderedCanvasIdPair> allPairs = new HashSet<>();

        for (final TileBounds tileBounds : sortedLayerTileBoundsList) {

            final List<TileBounds> sortedNearestNextLayerTileBoundsList =
                    nextLayerTileBoundsRTree.findTilesNearestToBox(tileBounds.getMinX(),
                                                                   tileBounds.getMinY(),
                                                                   tileBounds.getMaxX(),
                                                                   tileBounds.getMaxY(),
                                                                   mfovOffsetParameters.maxNeighborPixelDistance,
                                                                   mfovOffsetParameters.maxNeighborCount);

            if (sortedNearestNextLayerTileBoundsList.isEmpty()) {
                LOG.info("constructor: skipping tile {} in z {} because no nearest tiles found in z {}, stack={}, layerMFOV={}",
                         tileBounds.getTileId(),
                         tileBounds.getZ(),
                         nextLayerTileBoundsRTree.getZ(),
                         stackId.toDevString(),
                         layerMFOV);
            } else {

                LOG.debug("constructor: found {} nearest pairs for {} in {}",
                          sortedNearestNextLayerTileBoundsList.size(),
                          tileBounds.getTileId(),
                          layerMFOV);

                final Double deltaZ = tileBounds.getZ() - sortedNearestNextLayerTileBoundsList.get(0).getZ();
                final List<OrderedCanvasIdPair> sortedNearestPairsForCurrentLayerTile = new ArrayList<>();
                for (final TileBounds qTileBounds : sortedNearestNextLayerTileBoundsList) {

                    final CanvasId originalPCanvasId = new CanvasId(tileBounds.getSectionId(), tileBounds.getTileId());
                    final CanvasId originalQCanvasId = new CanvasId(qTileBounds.getSectionId(), qTileBounds.getTileId());
                    final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(originalPCanvasId,
                                                                             originalQCanvasId,
                                                                             deltaZ);
                    if (! allPairs.contains(pair)) {
                        allPairs.add(pair);
                        sortedNearestPairsForCurrentLayerTile.add(pair);
                        // LOG.debug("constructor: added {} with originalP {}", pair, originalPCanvasId); // include originalP in debug log to help identify cases where p and q get flipped
                    }

                }

                tileIdToSortedNearestPairsList.put(tileBounds.getTileId(), sortedNearestPairsForCurrentLayerTile);

            }
        }

    }

    /**
     * @return list of tile bounds
     */
    public List<String> getSortedTileIds() {
        return sortedLayerTileBoundsList.stream().map(TileBounds::getTileId).collect(Collectors.toList());
    }

    public List<OrderedCanvasIdPair> getSortedNearestPairsForTileId(final String tileId) {
        return tileIdToSortedNearestPairsList.get(tileId);
    }

    /**
     * Merge bounds in sortedLayerTileBoundsList with tile, section, and z from nearest pairs in next layer.
     *
     * @return list of TileBounds for the next layer.
     */
    private List<TileBounds> buildNextLayerTileBoundsListForComparisonArea() {
        final List<TileBounds> mergedBoundsList = new ArrayList<>();
        final Set<String> mergedNextLayerTileIds = new HashSet<>();
        for (final TileBounds currentTileBounds : sortedLayerTileBoundsList) {
            for (final OrderedCanvasIdPair pair : tileIdToSortedNearestPairsList.get(currentTileBounds.getTileId())) {

                final CanvasId p = pair.getP();
                final double pZ = Double.parseDouble(p.getGroupId());
                final CanvasId q = pair.getQ();
                final double qZ = Double.parseDouble(q.getGroupId());
 
                final CanvasId nextCanvasId = qZ > pZ ? q : p;
                final String nextTileId = nextCanvasId.getId();

                if (! mergedNextLayerTileIds.contains(nextTileId)) {
                    mergedNextLayerTileIds.add(nextTileId);
                    final String nextSectionId = nextCanvasId.getGroupId();
                    final Double nextZ = currentTileBounds.getZ() + pair.getAbsoluteDeltaZ();
                    mergedBoundsList.add(new TileBounds(nextTileId,
                                                        nextSectionId,
                                                        nextZ,
                                                        currentTileBounds.getMinX(), currentTileBounds.getMinY(),
                                                        currentTileBounds.getMaxX(), currentTileBounds.getMaxY()));
                    break; // only add the first next layer tile id for each current layer tile
                }
            }
        }

        if (mergedBoundsList.size() < sortedLayerTileBoundsList.size()) {
            LOG.warn("buildNextLayerTileBoundsListForComparisonArea: for {} {}, mergedBoundsList contains fewer tiles ({}) than sortedLayerTileBoundsList ({})",
                     stackId.toDevString(),
                     layerMFOV,
                     mergedBoundsList.size(),
                     sortedLayerTileBoundsList.size());
        }

        return mergedBoundsList;
    }

    /**
     * @param  stackWithZ             identifies the stack to process.
     * @param  renderDataClient       web service client for render stack data.
     * @param  mfovOffsetParameters   parameters for building MFOV offset stacks.
     * @param  mfovOffsetSupportData  support data for MFOV offset calculations.
     *
     * @return a list of SortedNearestCrossPairsForLayerMFOV objects for each z layer in the stack.
     *
     * @throws IOException
     *   if the build fails for any reason.
     */
    public static List<SortedNearestCrossPairsForLayerMFOV> buildPairsLists(final StackWithZValues stackWithZ,
                                                                            final RenderDataClient renderDataClient,
                                                                            final MFOVOffsetParameters mfovOffsetParameters,
                                                                            final MFOVOffsetSupportData mfovOffsetSupportData)
            throws IOException {

        LOG.info("buildPairsLists: entry, stackWithZ={}", stackWithZ);

        final List<SortedNearestCrossPairsForLayerMFOV> listOfMatchPairsLists = new ArrayList<>();

        final StackId stackId = stackWithZ.getStackId();
        final String stackDevString = stackId.toDevString();
        final String stackName = stackId.getStack();
        final List<Double> zValues = stackWithZ.getzValues();

        Map<LayerMFOV, List<TileBounds>> currentLayerMfovToMostConnectedTileBoundsMap = new HashMap<>();

        // grab the bounds for the best connected tile in each first layer MFOV and
        // wrap each bounds object in a list so that it can be used in the same manner
        // as the bounds pulled for all subsequent layers
        final Map<LayerMFOV, TileBounds> firstLayerMfovToTileBoundsMap =
                mfovOffsetSupportData.buildFirstLayerMfovToBestConnectedTileBoundsMap();
        for (final Map.Entry<LayerMFOV, TileBounds> entry : firstLayerMfovToTileBoundsMap.entrySet()) {
            final List<TileBounds> list = new ArrayList<>();
            list.add(entry.getValue());
            currentLayerMfovToMostConnectedTileBoundsMap.put(entry.getKey(), list);
        }

        for (int nextZIndex = 1; nextZIndex < zValues.size(); nextZIndex++) {

            final Double nextZ = zValues.get(nextZIndex);
            final List<TileBounds> tileBoundsListForNextEntireLayer = renderDataClient.getTileBounds(stackName, nextZ);
            final TileBoundsRTree tileBoundsRTreeForNextEntireLayer = new TileBoundsRTree(nextZ, tileBoundsListForNextEntireLayer);

            final List<LayerMFOV> currentLayerMFOVs =
                    currentLayerMfovToMostConnectedTileBoundsMap.keySet().stream().sorted().collect(Collectors.toList());
            final Map<LayerMFOV, List<TileBounds>> nextLayerMfovToMostConnectedTileBoundsMap = new HashMap<>();

            for (final LayerMFOV currentLayerMFOV : currentLayerMFOVs) {

                final List<TileBounds> currentLayerSortedTileBoundsListForComparisonArea =
                        currentLayerMfovToMostConnectedTileBoundsMap.get(currentLayerMFOV);

                final TileBounds firstCurrentLayerTileBounds = currentLayerSortedTileBoundsListForComparisonArea.get(0);
                LOG.debug("buildPairsLists: for {} {} and nextZ {} in {}, firstCurrentLayerTileBounds is {}",
                          stackDevString, currentLayerMFOV, nextZ, stackDevString, firstCurrentLayerTileBounds);

                final SortedNearestCrossPairsForLayerMFOV pairs =
                        new SortedNearestCrossPairsForLayerMFOV(stackId,
                                                                currentLayerMFOV,
                                                                currentLayerSortedTileBoundsListForComparisonArea,
                                                                tileBoundsRTreeForNextEntireLayer,
                                                                mfovOffsetParameters);
                listOfMatchPairsLists.add(pairs);

                final LayerMFOV nextLayerMFOV = new LayerMFOV(nextZ, currentLayerMFOV.getName());
                nextLayerMfovToMostConnectedTileBoundsMap.put(nextLayerMFOV,
                                                              pairs.buildNextLayerTileBoundsListForComparisonArea());
            }

            currentLayerMfovToMostConnectedTileBoundsMap = nextLayerMfovToMostConnectedTileBoundsMap;
        }

        return listOfMatchPairsLists;
    }

    private static final Logger LOG = LoggerFactory.getLogger(SortedNearestCrossPairsForLayerMFOV.class);
}
