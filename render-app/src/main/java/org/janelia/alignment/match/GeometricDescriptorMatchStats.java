package org.janelia.alignment.match;

import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;

/**
 * Statistics for a geometric desciptor match trial run.
 */
@SuppressWarnings("ALL")
public class GeometricDescriptorMatchStats
        implements Serializable {

    private final Integer pPeakCount;
    private final Long pPeakDerivationMilliseconds;
    private final Integer qPeakCount;
    private final Long qPeakDerivationMilliseconds;
    private final List<Integer> consensusSetSizes;
    private final Long matchDerivationMilliseconds;
    private final Double aggregateDeltaXStandardDeviation;
    private final Double aggregateDeltaYStandardDeviation;
    private final List<Double> consensusSetDeltaXStandardDeviations;
    private final List<Double> consensusSetDeltaYStandardDeviations;

    public GeometricDescriptorMatchStats() {
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

    public GeometricDescriptorMatchStats(final Integer pPeakCount,
                                         final Long pPeakDerivationMilliseconds,
                                         final Integer qPeakCount,
                                         final Long qPeakDerivationMilliseconds,
                                         final List<Integer> consensusSetSizes,
                                         final Long matchDerivationMilliseconds,
                                         final Double aggregateDeltaXStandardDeviation,
                                         final Double aggregateDeltaYStandardDeviation,
                                         final List<Double> consensusSetDeltaXStandardDeviations,
                                         final List<Double> consensusSetDeltaYStandardDeviations) {
        this.pPeakCount = pPeakCount;
        this.pPeakDerivationMilliseconds = pPeakDerivationMilliseconds;
        this.qPeakCount = qPeakCount;
        this.qPeakDerivationMilliseconds = qPeakDerivationMilliseconds;
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

    private static final JsonUtils.Helper<GeometricDescriptorMatchStats> JSON_HELPER =
            new JsonUtils.Helper<>(GeometricDescriptorMatchStats.class);

}
