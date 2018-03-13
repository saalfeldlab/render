package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.RealPointSampleList;
import net.imglib2.Sampler;
import net.imglib2.neighborsearch.NearestNeighborSearch;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.type.numeric.ARGBType;

/**
 * Converts a list of consensus set match pairs like this
 *
 * <pre>
 *            layer_a_set_ab_0   layer_a_set_ab_1
 *                    |                  |
 *            layer_b_set_ab_0   layer_b_set_ab_1
 *
 *
 *   layer_b_set_bc_0   layer_b_set_bc_1   layer_b_set_bc_2
 *           |                  |                  |
 *   layer_c_set_bc_0   layer_c_set_bc_1   layer_c_set_bc_2
 *
 * </pre>
 *
 * into a "consistent" list of pairs like this
 *
 * <pre>
 *
 *   layer_a_set_bc_0   layer_a_set_bc_1   layer_a_set_bc_2
 *           |                  |                  |
 *   layer_b_set_bc_0   layer_b_set_bc_1   layer_b_set_bc_2
 *           |                  |                  |
 *   layer_c_set_bc_0   layer_c_set_bc_1   layer_c_set_bc_2
 *
 * </pre>
 *
 * based upon the layer pair with the largest number of consensus sets.
 *
 * Match points from smaller consensus set layers are redistributed based upon their proximity
 * to the largest consensus set layer matches using Saalfeld's nearest neighbor logic.
 *
 * @author Eric Trautman
 */
public class DerivedMatchGroup {

    private final String groupId;
    private final List<CanvasMatches> derivedPairs;

    /**
     * Basic constructor.
     *
     * @param  groupId              the group identifier for this layer.
     * @param  multiConsensusPairs  collection of cross layer match pairs
     *                              that minimally includes all pairs for this group.
     */
    public DerivedMatchGroup(final String groupId,
                             final Collection<CanvasMatches> multiConsensusPairs) {
        this.groupId = groupId;
        this.derivedPairs = new ArrayList<>();

        derivePairs(multiConsensusPairs);
    }

    /**
     * @return list of "consistent" match pairs created by the match redistribution process.
     */
    public List<CanvasMatches> getDerivedPairs() {
        return derivedPairs;
    }

    private void derivePairs(final Collection<CanvasMatches> multiConsensusPairs) {

        String largestOutsideGroup = null;
        int largestOutsideGroupPairCount = 0;
        int originalTotalMatchPairCount = 0;

        final Map<String, List<CanvasMatches>> outsideGroupToPairs = new LinkedHashMap<>();
        List<CanvasMatches> pairList;
        String outsideGroup;
        for (final CanvasMatches pair : multiConsensusPairs) {
            if (groupId.equals(pair.getpGroupId())) {
                outsideGroup = pair.getqGroupId();
            } else if (groupId.equals(pair.getqGroupId())) {
                outsideGroup = pair.getpGroupId();
            } else {
                continue;
            }
            pairList = outsideGroupToPairs.get(outsideGroup);
            if (pairList == null) {
                pairList = new ArrayList<>();
                outsideGroupToPairs.put(outsideGroup, pairList);
            }
            pairList.add(pair);
            if (pairList.size() > largestOutsideGroupPairCount) {
                largestOutsideGroupPairCount = pairList.size();
                largestOutsideGroup = outsideGroup;
            }
            originalTotalMatchPairCount++;
        }

        final List<CanvasMatches> largestSetPairs = outsideGroupToPairs.get(largestOutsideGroup);

        LOG.info("derivePairs: processing {} total pairs, largest set has {} pairs",
                 originalTotalMatchPairCount, largestSetPairs.size());

        final RealPointSampleList<ARGBType> largestSetIndexSamples = new RealPointSampleList<>(2);
        final List<String> largestSetCanvasIds = new ArrayList<>();

        buildSamplesForLargestSet(largestSetPairs, largestSetIndexSamples, largestSetCanvasIds);

        derivedPairs.addAll(largestSetPairs);

        for (final String outGroup : outsideGroupToPairs.keySet()) {
            if (! outGroup.equals(largestOutsideGroup)) {
                final List<CanvasMatches> outsidePairs = outsideGroupToPairs.get(outGroup);
                for (final CanvasMatches outsidePair : outsidePairs) {
                    final List<RealPoint> originalPoints = getPointList(outsidePair);
                    final List<Integer> setIndexes = getSetIndexesForPoints(largestSetIndexSamples, originalPoints);
                    final List<CanvasMatches> splitMatches = splitMatches(outsidePair, setIndexes, largestSetCanvasIds);

                    derivedPairs.addAll(splitMatches);
                }
            }
        }

        LOG.info("derivePairs: exit, derived {} pairs", derivedPairs.size());
    }

    private void buildSamplesForLargestSet(final List<CanvasMatches> largestSetPairList,
                                           final RealPointSampleList<ARGBType> largestSetIndexSamples,
                                           final List<String> largestSetCanvasIds) {

        final Map<String, List<RealPoint>> idToPointList = mapCanvasIdsToPointLists(largestSetPairList);

        for (final String canvasId : idToPointList.keySet()) {
            final ARGBType canvasIdIndex = new ARGBType(largestSetCanvasIds.size());
            largestSetCanvasIds.add(canvasId);
            for (final RealPoint realPoint : idToPointList.get(canvasId)) {
                largestSetIndexSamples.add(realPoint, canvasIdIndex);
            }
        }
    }

    private Map<String, List<RealPoint>> mapCanvasIdsToPointLists(final List<CanvasMatches> consensusSetPairsList) {
        final Map<String, List<RealPoint>> idToPointList = new LinkedHashMap<>(); // HACK: order matters
        String canvasId;
        List<RealPoint> pointList;
        for (final CanvasMatches pair : consensusSetPairsList) {
            final boolean isP = groupId.equals(pair.getpGroupId());
            canvasId = isP ? pair.getpId() : pair.getqId();
            pointList = isP ? pair.getMatches().getPList() : pair.getMatches().getQList();
            idToPointList.put(canvasId, pointList);
        }
        return idToPointList;
    }

    private List<RealPoint> getPointList(final CanvasMatches pair) {
        final Matches matches = pair.getMatches();
        return groupId.equals(pair.getpGroupId()) ? matches.getPList() : matches.getQList();
    }

    private List<Integer> getSetIndexesForPoints(final RealPointSampleList<ARGBType> largestSetIndexSamples,
                                                 final List<RealPoint> otherSetPoints) {

        final List<Integer> setIndexList = new ArrayList<>();

        final KDTree<ARGBType> kdTree = new KDTree<>(largestSetIndexSamples);
        final NearestNeighborSearch<ARGBType> nnSearchSamples = new NearestNeighborSearchOnKDTree<>(kdTree);

        Sampler<ARGBType> sampler;
        ARGBType sampleItem;
        int nnSetIndex;
        for (final RealPoint otherPoint : otherSetPoints) {
            nnSearchSamples.search(otherPoint);
            sampler = nnSearchSamples.getSampler();

            sampleItem = sampler.get();
            nnSetIndex = sampleItem.get();
            setIndexList.add(nnSetIndex);
        }

        return setIndexList;
    }

    /**
     *
     * @param  originalPair         original point match pair whose point matches need to be split/distributed
     *                              to the appropriate largest group canvas.
     *
     * @param  setIndexes           list of consensus set numbers ordered by point match.
     *
     * @param  largestSetCanvasIds  list of largest group canvas ids ordered by consensus set number.
     *
     * @return list of match pairs with redistributed point matches.
     */
    private List<CanvasMatches> splitMatches(final CanvasMatches originalPair,
                                             final List<Integer> setIndexes,
                                             final List<String> largestSetCanvasIds) {

        final Matches originalMatches = originalPair.getMatches();
        final List<RealPoint> originalPs = originalMatches.getPList();
        final List<RealPoint> originalQs = originalMatches.getQList();
        final double[] originalWs = originalMatches.getWs();

        final Map<Integer, DynamicMatches> setIndexToSplitMatches = new HashMap<>();

        DynamicMatches splitMatches;
        for (int pointIndex = 0; pointIndex < setIndexes.size(); pointIndex++) {
            final int setIndex = setIndexes.get(pointIndex);
            splitMatches = setIndexToSplitMatches.get(setIndex);
            if (splitMatches == null) {
                splitMatches = new DynamicMatches(originalWs.length);
                setIndexToSplitMatches.put(setIndex, splitMatches);
            }
            splitMatches.addMatch(originalPs.get(pointIndex),
                                  originalQs.get(pointIndex),
                                  originalWs[pointIndex]);
        }

        final boolean forP = groupId.equals(originalPair.getpGroupId());

        CanvasId splitCanvasId;
        CanvasMatches splitPair;
        final List<CanvasMatches> list = new ArrayList<>(setIndexToSplitMatches.size());
        for (final Integer setIndex : setIndexToSplitMatches.keySet()) {

            splitMatches = setIndexToSplitMatches.get(setIndex);

            if (forP) {
                splitCanvasId = new CanvasId(originalPair.getpGroupId(), largestSetCanvasIds.get(setIndex));
                splitPair = new CanvasMatches(splitCanvasId.getGroupId(),
                                              splitCanvasId.getId(),
                                              originalPair.getqGroupId(),
                                              originalPair.getqId(),
                                              splitMatches.toMatches());
            } else {
                splitCanvasId = new CanvasId(originalPair.getqGroupId(), largestSetCanvasIds.get(setIndex));
                splitPair = new CanvasMatches(originalPair.getpGroupId(),
                                              originalPair.getpId(),
                                              splitCanvasId.getGroupId(),
                                              splitCanvasId.getId(),
                                              splitMatches.toMatches());
            }

            splitPair.setConsensusSetData(originalPair.getConsensusSetData());
            list.add(splitPair);

        }

        return list;
    }

    private static double[][] buildLocationArray(final List<RealPoint> pointList) {
        final double[][] locations = new double[2][pointList.size()];
        final double[] xLocations = locations[0];
        final double[] yLocations = locations[1];
        int i = 0;
        for (final RealPoint point : pointList) {
            xLocations[i] = point.getDoublePosition(0);
            yLocations[i] = point.getDoublePosition(1);
            i++;
        }
        return locations;
    }

    private static class DynamicMatches {

        private final List<RealPoint> pList;
        private final List<RealPoint> qList;
        private final List<Double> wList;

        public DynamicMatches(final int size) {
            this.pList = new ArrayList<>(size);
            this.qList = new ArrayList<>(size);
            this.wList = new ArrayList<>(size);
        }

        public void addMatch(final RealPoint p,
                             final RealPoint q,
                             final double w) {
            pList.add(p);
            qList.add(q);
            wList.add(w);
        }

        public Matches toMatches() {
            final double[][] ps = buildLocationArray(pList);
            final double[][] qs = buildLocationArray(qList);
            final double[] ws = wList.stream().mapToDouble(d -> d).toArray();
            return new Matches(ps, qs, ws);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(DerivedMatchGroup.class);

}
