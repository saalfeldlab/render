package org.janelia.alignment.match.stage;

import java.io.Serializable;
import java.util.List;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.PointMatchQualityStats;

/**
 * Statistics for one stage of matching a single canvas pair.
 *
 * @author Eric Trautman
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class StageMatchingStats
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
    private final Long overlappingImagePixels;
    private final Long overlappingCoveragePixels;
    private final Long matchQualityMilliseconds;

    public StageMatchingStats() {
        this(null,
             null,
             null,
             null,
             null,
             null,
             null,
             null);
    }

    public StageMatchingStats(final Integer pFeatureCount,
                              final Long pFeatureDerivationMilliseconds,
                              final Integer qFeatureCount,
                              final Long qFeatureDerivationMilliseconds,
                              final List<Integer> consensusSetSizes,
                              final Long matchDerivationMilliseconds,
                              final PointMatchQualityStats pointMatchQualityStats,
                              final Long matchQualityMilliseconds) {
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
            this.overlappingImagePixels = pointMatchQualityStats.getOverlappingImagePixels();
            this.overlappingCoveragePixels = pointMatchQualityStats.getOverlappingCoveragePixels();

        } else {

            this.aggregateDeltaXStandardDeviation = null;
            this.aggregateDeltaYStandardDeviation = null;
            this.consensusSetDeltaXStandardDeviations = null;
            this.consensusSetDeltaYStandardDeviations = null;
            this.overlappingImagePixels = null;
            this.overlappingCoveragePixels = null;
            
        }

        this.matchQualityMilliseconds = matchQualityMilliseconds;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<StageMatchingStats> JSON_HELPER =
            new JsonUtils.Helper<>(StageMatchingStats.class);

}
