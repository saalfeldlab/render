package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.RealPointSampleList;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.imglib2.type.numeric.integer.IntType;

/**
 * Utility methods to aggregate match pair data.
 *
 * @author Eric Trautman
 */
public class MatchAggregator {

    /**
     * Loop through specified pairs and aggregate matches for any pair that exceeds the specified
     * match count threshold.
     *
     * @param sourcePairs             match pairs to aggregate.
     * @param maxMatchesPerPair       aggregate any pair that has more than this number of matches.
     * @param matchAggregationRadius  pixel radius for aggregation.
     */
    public static void aggregateWithinRadius(final List<CanvasMatches> sourcePairs,
                                             final int maxMatchesPerPair,
                                             final double matchAggregationRadius) {

        // use random with same seed to ensure consistent results across different runs
        final Random random = new Random(1332441549191L);

        for (final CanvasMatches pair : sourcePairs) {

            if (pair.getMatchCount() > maxMatchesPerPair) {

                // build a kd tree of all p tile points mapped to their match index
                final Matches originalMatches = pair.getMatches();
                final List<RealPoint> originalPList = originalMatches.getPList();
                final RealPointSampleList<IntType> pPointSampleList = new RealPointSampleList<>(2);
                for (int i = 0; i < originalPList.size(); i++) {
                    pPointSampleList.add(originalPList.get(i), new IntType(i));
                }
                final KDTree<IntType> kdTree = new KDTree<>(pPointSampleList);

                // list of match indexes that have not been covered by a prior radius search
                final LinkedList<Integer> remainingIndexes =
                        IntStream.range(0, originalPList.size())
                                .boxed().collect(Collectors.toCollection(LinkedList::new));

                // list of radius search results
                // - each result contains a list of points to be aggregated into a single point
                final List<RadiusNeighborSearchOnKDTree<IntType>> radiusSearchResultList = new ArrayList<>();

                // keep combining points until we have fewer than maxMatchesPerPair ...
                int matchIndex;
                while ((remainingIndexes.size() > 0) &&
                       (remainingIndexes.size() + radiusSearchResultList.size()) > maxMatchesPerPair) {

                    // randomly choose next point for radius search
                    final int randomIndex = random.nextInt(remainingIndexes.size());
                    matchIndex = remainingIndexes.get(randomIndex);
                    final RealPoint randomPoint = originalPList.get(matchIndex);

                    final RadiusNeighborSearchOnKDTree<IntType> radius = new RadiusNeighborSearchOnKDTree<>(kdTree);
                    radius.search(randomPoint, matchAggregationRadius, false);

                    if (radius.numNeighbors() < 1) {
                        throw new IllegalStateException("radius search came back empty for point " + randomPoint +
                                                        " with matchIndex " + matchIndex + " in tile " + pair.getpId());
                    }

                    radiusSearchResultList.add(radius);

                    for (int i = 0; i < radius.numNeighbors(); i++) {
                        matchIndex = radius.getSampler(i).get().get();
                        remainingIndexes.remove(Integer.valueOf(matchIndex));
                    }
                }

                final int aggregatedMatchCount = remainingIndexes.size() + radiusSearchResultList.size();
                if (aggregatedMatchCount > maxMatchesPerPair) {
                    LOG.warn("aggregateMatches: aggregatedMatchCount {} still exceeds max {} for pair {}, maybe radius {} is too small",
                             aggregatedMatchCount, maxMatchesPerPair, pair.toKeyString(), matchAggregationRadius);
                }

                // raw values for original
                final double[][] originalPs = originalMatches.getPs();
                final double[][] originalQs = originalMatches.getQs();
                final double[] originalWs = originalMatches.getWs();

                // raw values for aggregated result (to be populated)
                final double[][] aggregatedPs = new double[2][aggregatedMatchCount];
                final double[][] aggregatedQs = new double[2][aggregatedMatchCount];
                final double[] aggregatedWs = new double[aggregatedMatchCount];

                // first copy any remaining (not aggregated) matches directly from source
                int aggregatedMatchIndex = 0;
                for (final Integer remainingIndex : remainingIndexes) {
                    for (int d = 0; d < 2; d++) {
                        aggregatedPs[d][aggregatedMatchIndex] = originalPs[d][remainingIndex];
                        aggregatedQs[d][aggregatedMatchIndex] = originalQs[d][remainingIndex];
                    }
                    aggregatedWs[aggregatedMatchIndex] = originalWs[remainingIndex];
                    aggregatedMatchIndex++;
                }

                // then aggregate radius search point groups into single points
                final List<RealPoint> originalQList = originalMatches.getQList();
                for (final RadiusNeighborSearchOnKDTree<IntType> radius : radiusSearchResultList) {

                    // TODO: update this to properly aggregate values rather than just using first value
                    RealPoint aggregatedPPoint = null;
                    RealPoint aggregatedQPoint = null;
                    double aggregatedWeight = 0.0;

                    for (int i = 0; i < radius.numNeighbors(); i++) {
                        matchIndex = radius.getSampler(i).get().get();
                        final RealPoint pPoint = originalPList.get(matchIndex);
                        final RealPoint qPoint = originalQList.get(matchIndex);
                        if (i == 0) {
                            aggregatedPPoint = pPoint;
                            aggregatedQPoint = qPoint;
                            aggregatedWeight = originalWs[matchIndex];
                        } else {
                            break; // TODO: remove this hack
                        }
                    }

                    for (int d = 0; d < 2; d++) {
                        aggregatedPs[d][aggregatedMatchIndex] = aggregatedPPoint.getDoublePosition(d);
                        aggregatedQs[d][aggregatedMatchIndex] = aggregatedQPoint.getDoublePosition(d);
                    }
                    aggregatedWs[aggregatedMatchIndex] = aggregatedWeight;
                    aggregatedMatchIndex++;
                }

                // replace pair's original matches with aggregated ones
                pair.setMatches(new Matches(aggregatedPs, aggregatedQs, aggregatedWs));
            }

        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchAggregator.class);
}
