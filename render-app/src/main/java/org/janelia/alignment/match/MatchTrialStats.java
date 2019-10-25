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

    public MatchTrialStats() {
        this(null,
             null,
             null,
             null,
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
                           final Double aggregateDeltaXStandardDeviation,
                           final Double aggregateDeltaYStandardDeviation,
                           final List<Double> consensusSetDeltaXStandardDeviations,
                           final List<Double> consensusSetDeltaYStandardDeviations) {
        this.pFeatureCount = pFeatureCount;
        this.pFeatureDerivationMilliseconds = pFeatureDerivationMilliseconds;
        this.qFeatureCount = qFeatureCount;
        this.qFeatureDerivationMilliseconds = qFeatureDerivationMilliseconds;
        this.consensusSetSizes = consensusSetSizes;
        this.matchDerivationMilliseconds = matchDerivationMilliseconds;
        this.aggregateDeltaXStandardDeviation = aggregateDeltaXStandardDeviation;
        this.aggregateDeltaYStandardDeviation = aggregateDeltaYStandardDeviation;
        this.consensusSetDeltaXStandardDeviations = consensusSetDeltaXStandardDeviations;
        this.consensusSetDeltaYStandardDeviations = consensusSetDeltaYStandardDeviations;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<MatchTrialStats> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrialStats.class);

}
