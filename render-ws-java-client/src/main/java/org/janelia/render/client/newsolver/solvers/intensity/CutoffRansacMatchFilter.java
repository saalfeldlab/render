package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.AffineModel1D;
import mpicbg.models.PointMatch;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A match filter for cross-layer intensity matching with a per-pixel-match cutoff. It works like
 * {@link RansacMatchFilter} (bin pixel matches, then RANSAC-reduce to two surrogate matches that yield the same
 * affine model), but first discards individual pixel matches whose intensity shift {@code |q - p|} exceeds a cutoff.
 * This suppresses spurious matches at abrupt intensity transitions in z, e.g. where tissue transitions into resin.
 * <p>
 * Because it operates per pixel match, it requires pixel-by-pixel matching and cannot be combined with the
 * percentile-based {@link HistogramMatchFilter} (which discards the pixel correspondence).
 * <p>
 * If fewer than two matches survive the cutoff (so an affine model cannot be reliably fit), two weightless
 * placeholder matches are returned. These keep the coefficient-tile pair structurally consistent (they satisfy the
 * affine model's minimum match count) but, having zero weight, drop out of every weighted fit and therefore add no
 * constraint to the solve.
 */
public class CutoffRansacMatchFilter implements MatchFilter {

	// use the fact that the float intensity values in [0,1] originate from integers in [0,255]
	private static final int N_BINS = 256;

	// maximum allowed per-pixel intensity shift |q - p|, in normalized [0,1] units
	private final double cutoff;

	/**
	 * @param cutoff maximum allowed per-pixel intensity shift {@code |q - p|}, in normalized [0,1] units
	 */
	public CutoffRansacMatchFilter(final double cutoff) {
		this.cutoff = cutoff;
	}

	@Override
	public List<PointMatch> filter(final FlatIntensityMatches matches) {
		final List<PointMatch> compressedCandidates = compressByBinningWithinCutoff(matches);

		// use a fresh model/filter per call so that this filter can be safely shared across matching threads
		final RansacRegressionReduceFilter reduceFilter = new RansacRegressionReduceFilter(new AffineModel1D());
		final List<PointMatch> inliers = new ArrayList<>();
		reduceFilter.filter(compressedCandidates, inliers);

		// an affine model needs at least two matches; if too few survived the cutoff, add no real constraint
		if (inliers.size() < 2)
			return weightlessPlaceholderMatches();

		return inliers;
	}

	private List<PointMatch> compressByBinningWithinCutoff(final FlatIntensityMatches candidates) {
		// bin the matches into N_BINS x N_BINS bins and sum their weights, dropping matches beyond the cutoff
		final Map<Pair<Integer, Integer>, Double> pairToWeights = new HashMap<>(N_BINS * N_BINS);
		for (int k = 0; k < candidates.size(); k++) {
			// discard matches whose per-pixel intensity shift exceeds the cutoff
			if (Math.abs(candidates.q[k] - candidates.p[k]) > cutoff)
				continue;

			final int p = (int) Math.round(candidates.p[k] * N_BINS);
			final int q = (int) Math.round(candidates.q[k] * N_BINS);
			final Pair<Integer, Integer> pair = new ValuePair<>(p, q);
			pairToWeights.merge(pair, candidates.w[k], Double::sum);
		}

		// create new compressed candidates from the binned matches
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

	private static List<PointMatch> weightlessPlaceholderMatches() {
		// two distinct, deterministic points with weight 0: they satisfy the affine model's minimum match count
		// (keeping the pair structurally consistent), but drop out of every weighted fit and so add no constraint
		final List<PointMatch> placeholders = new ArrayList<>(2);
		placeholders.add(new PointMatch1D(new Point1D(0.0), new Point1D(0.0), 0.0));
		placeholders.add(new PointMatch1D(new Point1D(1.0), new Point1D(1.0), 0.0));
		return placeholders;
	}
}
