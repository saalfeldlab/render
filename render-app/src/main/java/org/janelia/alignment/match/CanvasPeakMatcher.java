package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.pointdescriptor.matcher.Matcher;
import mpicbg.pointdescriptor.matcher.SubsetMatcher;
import mpicbg.util.Timer;

import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import plugin.DescriptorParameters;
import process.Matching;
import process.Particle;

import static process.GeometricDescriptorMatcher.getCorrespondenceCandidatesPublic;

/**
 * Derives geometric descriptor peak match results for the specified canvases, filtering out outlier matches.
 *
 * @author Stephan Preibisch
 */
public class CanvasPeakMatcher
        implements Serializable {

    private final DescriptorParameters descriptorParameters;
    private final MatchFilter matchFilter;

    /**
     * Sets up everything that is needed to derive geometric descriptor match results from the peak lists of two canvases.
     */
    public CanvasPeakMatcher(final DescriptorParameters descriptorParameters,
                             final MatchDerivationParameters matchParameters) {
        this.descriptorParameters = descriptorParameters;
        this.matchFilter = new MatchFilter(matchParameters);
    }

    /**
     * @return match results for the specified geometric descriptor peaks.
     */
    CanvasMatchResult deriveMatchResult(final List<DifferenceOfGaussianPeak<FloatType>> canvas1Peaks,
                                        final List<DifferenceOfGaussianPeak<FloatType>> canvas2Peaks) {

        LOG.info("deriveMatchResult: entry");

        final Timer timer = new Timer();
        timer.start();

        final List<PointMatch> candidates = getCandidateMatches(canvas1Peaks, canvas2Peaks);

        final CanvasMatchResult result = matchFilter.buildMatchResult(candidates);

        LOG.info("deriveMatchResult: exit, result={}, elapsedTime={}s", result, (timer.stop() / 1000));

        return result;
    }

    /**
     * Adaptation of {@link Matching#descriptorBasedRegistration} that excludes filtering and user interface code.
     *
     * @return list of candidate inlier matches for filtering.
     */
    private List<PointMatch> getCandidateMatches(final List<DifferenceOfGaussianPeak<FloatType>> canvas1Peaks,
                                                 final List<DifferenceOfGaussianPeak<FloatType>> canvas2Peaks) {

        final float zStretching = 1; // dimensionality is always 2, so set zStretching to 1

        final String explanation = "";
        final Matcher matcher =
                new SubsetMatcher(descriptorParameters.numNeighbors,
                                  descriptorParameters.numNeighbors + descriptorParameters.redundancy);
        final List<PointMatch> candidates;

        // NOTE: for now, similarOrientation will always be true ...

        // if the images are already in similar orientation, we do not do a rotation-invariant matching, but only translation-invariant
        if (descriptorParameters.similarOrientation) {

            // an empty model with identity transform
            final Model<?> identityTransform = descriptorParameters.getInitialModel();// = params.model.copy();

            candidates = getCorrespondenceCandidatesPublic(descriptorParameters.significance,
                                                           matcher,
                                                           canvas1Peaks,
                                                           canvas2Peaks,
                                                           identityTransform,
                                                           descriptorParameters.dimensionality,
                                                           zStretching,
                                                           zStretching,
                                                           explanation);

            // before we compute the RANSAC we will reset the coordinates of all points so that we directly get the correct model
            for (final PointMatch pm : candidates) {
                ((Particle) pm.getP1()).restoreCoordinates();
                ((Particle) pm.getP2()).restoreCoordinates();
            }

        } else {

            candidates = getCorrespondenceCandidatesPublic(descriptorParameters.significance,
                                                           matcher,
                                                           canvas1Peaks,
                                                           canvas2Peaks,
                                                           null,
                                                           descriptorParameters.dimensionality,
                                                           zStretching,
                                                           zStretching,
                                                           explanation);
        }

        return candidates;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CanvasPeakMatcher.class);
}
