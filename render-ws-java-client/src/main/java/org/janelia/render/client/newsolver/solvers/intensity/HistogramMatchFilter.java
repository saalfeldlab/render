package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.AffineModel1D;
import mpicbg.models.PointMatch;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import org.janelia.render.client.intensityadjust.intensity.PointMatchFilter;
import org.janelia.render.client.intensityadjust.intensity.RansacRegressionReduceFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A match filter that
 * - creates matches based on percentiles of the intensity distributions
 * - reduces the matches to two surrogate matches that yield the same affine model when fitted
 * Note: this ignores match weights!
 */
public class HistogramMatchFilter implements MatchFilter {
    final AffineModel1D model = new AffineModel1D();

    @Override
    public List<PointMatch> filter(final FlatIntensityMatches matches) {
        // Sort values to easily get percentiles
        final double[] sortedPValues = Arrays.copyOf(matches.p, matches.size());
        Arrays.sort(sortedPValues);
        final double[] sortedQValues = Arrays.copyOf(matches.q, matches.size());
        Arrays.sort(sortedQValues);

        // Create percentile matches
        final int nValues = sortedPValues.length;
        final int nSamples = Math.max(nValues, 100);
        final List<PointMatch> candidates = new ArrayList<>(nSamples);
        for (int i = 0; i < nSamples; i++) {
            final double percentile = (i + 0.5) / nSamples;
            final int index = Math.min((int) (percentile * nValues), nValues - 1);
            final Point1D p = new Point1D(sortedPValues[index]);
            final Point1D q = new Point1D(sortedQValues[index]);
            candidates.add(new PointMatch(p, q, 1.0));
        }

        // Fit an affine model to the percentile matches
        try {
            model.fit(candidates);
        } catch (final Exception e) {
            throw new RuntimeException("Error fitting affine model to histogram matches", e);
        }

        // Reduce to two surrogate matches that yield the same model
        final double min = sortedPValues[0];
        final double max = sortedPValues[nValues - 1];
        final Point1D p1 = new Point1D(min);
        final Point1D q1 = new Point1D(model.apply(p1.getL())[0]);
        final Point1D p2 = new Point1D(max);
        final Point1D q2 = new Point1D(model.apply(p2.getL())[0]);

        final List<PointMatch> reducedCandidates = new ArrayList<>(2);
        reducedCandidates.add(new PointMatch(p1, q1, 1.0));
        reducedCandidates.add(new PointMatch(p2, q2, 1.0));

        return reducedCandidates;
    }
}
