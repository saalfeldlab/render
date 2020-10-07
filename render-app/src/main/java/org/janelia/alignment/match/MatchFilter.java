package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.parameters.MatchDerivationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility to filter a set of point match correspondences.
 * Core logic stolen from Stephan Saalfeld <saalfelds@janelia.hhmi.org>.
 *
 * @author Eric Trautman
 */
public class MatchFilter
        implements Serializable {

    /** Supported filtering options. */
    public enum FilterType {
        /** Skip filtering. */
        NONE,

        /** Filter inliers into a single set. */
        SINGLE_SET,

        /** Filter inliers into potentially multiple consensus sets. */
        CONSENSUS_SETS,

        /** Filter inliers into potentially multiple consensus sets and then aggregate them back into one set. */
        AGGREGATED_CONSENSUS_SETS
    }

    private final ModelType modelType;
    private final ModelType regularizerModelType;
    private final Double interpolatedModelLambda;
    private final int iterations;
    private final float maxEpsilon;
    private final float minInlierRatio;
    private final double maxTrust;
    private final int minNumInliers;
    private final Integer maxNumInliers;
    private final FilterType filterType;

    /**
     * Sets up everything that is needed to derive point matches from the feature lists of two canvases.
     */
    public MatchFilter(final MatchDerivationParameters matchParameters,
                       final double renderScale) {
        this.modelType = matchParameters.matchModelType;
        this.regularizerModelType = matchParameters.matchRegularizerModelType;
        this.interpolatedModelLambda = matchParameters.matchInterpolatedModelLambda;
        this.iterations = matchParameters.matchIterations;
        this.maxEpsilon = matchParameters.getMatchMaxEpsilonForRenderScale(renderScale);
        this.minInlierRatio = matchParameters.matchMinInlierRatio;
        this.minNumInliers = matchParameters.matchMinNumInliers;
        this.maxTrust = matchParameters.matchMaxTrust;
        this.maxNumInliers = matchParameters.matchMaxNumInliers;
        this.filterType = matchParameters.matchFilter;
    }

    FilterType getFilterType() {
        return filterType;
    }

    public List<PointMatch> filterMatches(final List<PointMatch> candidates,
                                          final Model model) {

        final List<PointMatch> inliers = new ArrayList<>(candidates.size());

        if (candidates.size() > 0) {
            try {
                model.filterRansac(candidates,
                                   inliers,
                                   iterations,
                                   maxEpsilon,
                                   minInlierRatio,
                                   minNumInliers,
                                   maxTrust);
            } catch (final NotEnoughDataPointsException e) {
                LOG.warn("failed to filter outliers", e);
            }

            postProcessInliers(inliers);

        }

        LOG.info("filterMatches: filtered {} inliers from {} candidates", inliers.size(), candidates.size());

        return inliers;
    }

    /**
     * Logic stolen from:
     *
     * <a href="https://github.com/saalfeldlab/hot-knife/blob/master/src/main/java/org/janelia/saalfeldlab/hotknife/MultiConsensusFilter.java>
     *     Saalfeld's Hot Knife MultiConsensusFilter
     * </a>
     *
     * @param  candidates  list of all candidate matches.
     *
     * @return list of consensus set match lists in order of quality.
     */
    public List<List<PointMatch>> filterConsensusMatches(final List<PointMatch> candidates) {

        final List<List<PointMatch>> listOfInliersLists = new ArrayList<>();
        final int totalNumberOfCandidates = candidates.size();

        boolean modelFound;
        do {
            final Model model = getModel();
            final List<PointMatch> modelInliers = new ArrayList<>();
            try {
                modelFound = model.filterRansac(candidates,
                                                modelInliers,
                                                iterations,
                                                maxEpsilon,
                                                minInlierRatio,
                                                minNumInliers);
            } catch (final NotEnoughDataPointsException e) {
                modelFound = false;
            }

            if (modelFound) {
                listOfInliersLists.add(modelInliers);
                candidates.removeAll(modelInliers);
            }

        } while (modelFound);

        // additional post processing of inliers is needed to apply maxNumInliers constraint and address minNumInliers bug

        final List<List<PointMatch>> processedListOfInliersLists = new ArrayList<>(listOfInliersLists.size());
        final List<Integer> consensusSetSizes = new ArrayList<>();
        int totalNumberOfInliers = 0;

        for (int i = 0; i < listOfInliersLists.size(); i++) {
            final List<PointMatch> modelInliers = listOfInliersLists.get(i);
            postProcessInliers(modelInliers);
            if (modelInliers.size() > 0) {
                processedListOfInliersLists.add(modelInliers);
                consensusSetSizes.add(modelInliers.size());
                totalNumberOfInliers += modelInliers.size();
            } else {
                LOG.warn("dropped consensus set {} because it was empty after post processing", i);
            }
        }

        LOG.info("filterConsensusMatches: filtered {} inlier set(s) with sizes {} for a total of {} inliers from {} candidates",
                 processedListOfInliersLists.size(), consensusSetSizes, totalNumberOfInliers, totalNumberOfCandidates);

        return processedListOfInliersLists;
    }

    /**
     * @return model instance for match filtering.
     */
    public Model getModel() {
        final Model model;
        if (interpolatedModelLambda == null) {
            model = modelType.getInstance();
        } else {
            model = modelType.getInterpolatedInstance(regularizerModelType, interpolatedModelLambda);
        }
        return model;
    }

    private void postProcessInliers(final List<PointMatch> inliers) {

        // TODO: remove this extra check once RANSAC filter issue is fixed
        if ((inliers.size() > 0) && (inliers.size() < minNumInliers)) {
            LOG.warn("removing {} inliers that mysteriously did not get removed with minNumInliers value of {}",
                     inliers.size(), minNumInliers);
            inliers.clear();
        }

        if ((maxNumInliers != null) && (maxNumInliers > 0) && (inliers.size() > maxNumInliers)) {
            LOG.info("filterMatches: randomly selecting {} of {} inliers", maxNumInliers, inliers.size());
            // randomly select maxNumInliers elements by shuffling and then remove excess elements
            Collections.shuffle(inliers);
            inliers.subList(maxNumInliers, inliers.size()).clear();
        }
    }

    public CanvasMatchResult buildMatchResult(final List<PointMatch> candidates) {

        CanvasMatchResult result = null;
        switch (filterType) {
            case NONE:
                result = new CanvasMatchResult(this, Collections.singletonList(candidates), candidates.size());
                break;
            case SINGLE_SET:
                final Model model = getModel();
                final List<PointMatch> inliers = filterMatches(candidates, model);
                result = new CanvasMatchResult(this, Collections.singletonList(inliers), candidates.size());
                break;
            case CONSENSUS_SETS:
                final List<List<PointMatch>> consensusMatches = filterConsensusMatches(candidates);
                result = new CanvasMatchResult(this, consensusMatches, candidates.size());
                break;
            case AGGREGATED_CONSENSUS_SETS:
                final List<PointMatch> aggregatedMatches = new ArrayList<>(candidates.size());
                filterConsensusMatches(candidates).forEach(aggregatedMatches::addAll);

                result = new CanvasMatchResult(this, Collections.singletonList(aggregatedMatches), candidates.size());
                break;
        }

        return result;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchFilter.class);
}
