package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Statistics for a match trial run.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("ALL")
public class MatchTrialStats
        implements Serializable {

    private final Integer pFeatureCount;
    private final Long pFeatureDerivationMilliseconds;
    private final Integer qFeatureCount;
    private final Long qFeatureDerivationMilliseconds;
    private final List<Integer> consensusSetSizes;
    private final Long matchDerivationMilliseconds;
    private final Double aggregateDeltaXStandardDeviation;
    private final Double aggregateDeltaYStandardDeviation;
    private final List<Double> consensusSetDeltaXStandardDeviations;
    private final List<Double> consensusSetDeltaYStandardDeviations;
    private final Long pImageArea;
    private final Long qImageArea;
    private final Long pConvexHullArea;
    private final Long qConvexHullArea;

    public MatchTrialStats() {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null);
    }

    public MatchTrialStats(final Integer pFeatureCount,
                           final Long pFeatureDerivationMilliseconds,
                           final Integer qFeatureCount,
                           final Long qFeatureDerivationMilliseconds,
                           final List<Integer> consensusSetSizes,
                           final Long matchDerivationMilliseconds,
                           final PointMatchQualityStats pointMatchQualityStats) {
        this.pFeatureCount = pFeatureCount;
        this.pFeatureDerivationMilliseconds = pFeatureDerivationMilliseconds;
        this.qFeatureCount = qFeatureCount;
        this.qFeatureDerivationMilliseconds = qFeatureDerivationMilliseconds;
        this.consensusSetSizes = consensusSetSizes;
        this.matchDerivationMilliseconds = matchDerivationMilliseconds;

        if (pointMatchQualityStats != null) {

            final double[] aggregateDeltaXAndYStandardDeviation =
                    pointMatchQualityStats.getAggregateDeltaXAndYStandardDeviation();
            this.aggregateDeltaXStandardDeviation = aggregateDeltaXAndYStandardDeviation[0];
            this.aggregateDeltaYStandardDeviation = aggregateDeltaXAndYStandardDeviation[1];
            this.consensusSetDeltaXStandardDeviations =
                    pointMatchQualityStats.getConsensusSetDeltaXStandardDeviations();
            this.consensusSetDeltaYStandardDeviations =
                    pointMatchQualityStats.getConsensusSetDeltaYStandardDeviations();
            this.pImageArea = pointMatchQualityStats.getpImageArea();
            this.pConvexHullArea = pointMatchQualityStats.getpConvexHullArea().longValue();
            this.qImageArea = pointMatchQualityStats.getqImageArea();
            this.qConvexHullArea = pointMatchQualityStats.getqConvexHullArea().longValue();

        } else {

            this.aggregateDeltaXStandardDeviation = null;
            this.aggregateDeltaYStandardDeviation = null;
            this.consensusSetDeltaXStandardDeviations = null;
            this.consensusSetDeltaYStandardDeviations = null;
            this.pImageArea = null;
            this.pConvexHullArea = null;
            this.qImageArea = null;
            this.qConvexHullArea = null;
            
        }

    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<MatchTrialStats> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrialStats.class);

}
