package org.janelia.render.client.newsolver.solvers.intensity;

import mpicbg.models.PointMatch;

import java.util.List;


/**
 * A filter that takes flat intensity matches and produces a filtered set of matches.
 */
interface MatchFilter {

    /**
     * Filter the given flat intensity matches and return the filtered matches.
     * @param matches the flat intensity matches to filter
     * @return the filtered intensity matches
     */
    List<PointMatch> filter(FlatIntensityMatches matches);
}
