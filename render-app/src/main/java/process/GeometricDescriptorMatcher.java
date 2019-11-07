package process;

import java.util.ArrayList;
import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.pointdescriptor.matcher.Matcher;

import static process.Matching.getCorrespondenceCandidates;

/**
 * Exposes package protected methods of ImageJ Geometric Descriptor Matching plugin for use within Render eco-system.
 *
 * @author Stephan Preibisch
 */
public class GeometricDescriptorMatcher {

    public static ArrayList<PointMatch> getCorrespondenceCandidatesPublic(final double nTimesBetter,
                                                                          final Matcher matcher,
                                                                          final List<DifferenceOfGaussianPeak<FloatType>> peaks1,
                                                                          final List<DifferenceOfGaussianPeak<FloatType>> peaks2,
                                                                          final Model<?> model,
                                                                          final int dimensionality,
                                                                          final float zStretching1,
                                                                          final float zStretching2,
                                                                          final String explanation) {
        // TODO: it would be nice if ArrayList cast wasn't needed for peak lists
        return getCorrespondenceCandidates(nTimesBetter,
                                           matcher,
                                           (ArrayList<DifferenceOfGaussianPeak<FloatType>>) peaks1,
                                           (ArrayList<DifferenceOfGaussianPeak<FloatType>>) peaks2,
                                           model,
                                           dimensionality,
                                           zStretching1,
                                           zStretching2,
                                           explanation);
    }

}
