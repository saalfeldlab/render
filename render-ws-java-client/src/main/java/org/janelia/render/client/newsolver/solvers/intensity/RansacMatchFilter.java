package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.AffineModel1D;
import mpicbg.models.PointMatch;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A match filter that
 * - compresses intensity matches by binning them into nBins x nBins bins to reduce redundant matches
 * - applies RANSAC to filter out outliers
 * - reduces the matches to two surrogate matches that yield the same affine model when fitted
 */
public class RansacMatchFilter implements MatchFilter {
    private static final int N_BINS = 256;
    private final PointMatchFilter filter;

    /**
     * Create a new RANSAC match filter with 256 bins.
     */
    public RansacMatchFilter() {
        this.filter = new RansacRegressionReduceFilter(new AffineModel1D());
    }

    @Override
    public List<PointMatch> filter(final FlatIntensityMatches matches) {
        final List<PointMatch> compressedCandidates = compressByBinning(matches);

        final List<PointMatch> inliers = new ArrayList<>();
        filter.filter(compressedCandidates, inliers);
        return inliers;
    }

    private static List<PointMatch> compressByBinning(final FlatIntensityMatches candidates) {
        // Bin the matches into nBins x nBins bins and sum their weights
        final Map<Pair<Integer, Integer>, Double> pairToWeights = new HashMap<>(N_BINS * N_BINS);
        for (int k = 0; k < candidates.size(); k++) {
            // Use the fact that the float intensity values in the range [0, 1]
            // originate from integers in the range [0, 255]
            final int p = (int) Math.round(candidates.p[k] * N_BINS);
            final int q = (int) Math.round(candidates.q[k] * N_BINS);
            final Pair<Integer, Integer> pair = new ValuePair<>(p, q);
            final double previousWeight = pairToWeights.getOrDefault(pair, 0.0);
            pairToWeights.put(pair, previousWeight + candidates.w[k]);
        }

        // Create new compressed candidates from the binned matches
        final List<PointMatch> compressedCandidates = new ArrayList<>(pairToWeights.size());
        for (final Map.Entry<Pair<Integer, Integer>, Double> entry : pairToWeights.entrySet()) {
            final Pair<Integer, Integer> pair = entry.getKey();
            final double weight = entry.getValue();
            final Point1D p1 = new Point1D((double) pair.getA() / N_BINS);
            final Point1D p2 = new Point1D((double) pair.getB() / N_BINS);
            compressedCandidates.add(new PointMatch1D(p1, p2, weight));
        }

        return compressedCandidates;
    }
}
