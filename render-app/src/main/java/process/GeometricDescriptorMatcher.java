package process;

import java.util.ArrayList;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.pointdescriptor.matcher.Matcher;
import mpicbg.pointdescriptor.matcher.SubsetMatcher;

import plugin.DescriptorParameters;

import static process.Matching.getCorrespondenceCandidates;

/**
 * Adapts ImageJ Geometric Descriptor Matching plugin for use within Render eco-system.
 *
 * @author Stephan Preibisch
 */
public class GeometricDescriptorMatcher {

    /**
     * Adaptation of {@link Matching#descriptorBasedRegistration} that excludes filtering and user interface code.
     *
     * @return list of candidate inlier matches for filtering.
     */
    public static List<PointMatch> descriptorBasedRegistration(final List<DifferenceOfGaussianPeak<FloatType>> canvas1Peaks,
                                                               final List<DifferenceOfGaussianPeak<FloatType>> canvas2Peaks,
                                                               final DescriptorParameters params) {

        final float zStretching = 1; // dimensionality is always 2, so set zStretching to 1

        final String explanation = "";
        final Matcher matcher = new SubsetMatcher(params.numNeighbors,
                                                  params.numNeighbors + params.redundancy);
        final List<PointMatch> candidates;

        // TODO: it would be nice if ArrayList cast wasn't needed for peak lists
        final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks1 =
                (ArrayList<DifferenceOfGaussianPeak<FloatType>>) canvas1Peaks;
        final ArrayList<DifferenceOfGaussianPeak<FloatType>> peaks2 =
                (ArrayList<DifferenceOfGaussianPeak<FloatType>>) canvas2Peaks;

        // NOTE: for now, similarOrientation will always be true ...

        // if the images are already in similar orientation, we do not do a rotation-invariant matching, but only translation-invariant
        if (params.similarOrientation) {

            // an empty model with identity transform
            final Model<?> identityTransform = params.getInitialModel();// = params.model.copy();

            candidates = getCorrespondenceCandidates(params.significance,
                                                     matcher,
                                                     peaks1,
                                                     peaks2,
                                                     identityTransform,
                                                     params.dimensionality,
                                                     zStretching,
                                                     zStretching,
                                                     explanation);

            // before we compute the RANSAC we will reset the coordinates of all points so that we directly get the correct model
            for (final PointMatch pm : candidates) {
                ((Particle) pm.getP1()).restoreCoordinates();
                ((Particle) pm.getP2()).restoreCoordinates();
            }

        } else {

            candidates = getCorrespondenceCandidates(params.significance,
                                                     matcher,
                                                     peaks1,
                                                     peaks2,
                                                     null,
                                                     params.dimensionality,
                                                     zStretching,
                                                     zStretching,
                                                     explanation);
        }

        return candidates;
    }

}
