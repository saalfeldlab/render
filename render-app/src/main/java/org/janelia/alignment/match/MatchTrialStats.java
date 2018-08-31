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

    public MatchTrialStats() {
        this(null,
             null,
             null,
             null,
             null,
             null);
    }

    MatchTrialStats(final Integer pFeatureCount,
                    final Long pFeatureDerivationMilliseconds,
                    final Integer qFeatureCount,
                    final Long qFeatureDerivationMilliseconds,
                    final List<Integer> consensusSetSizes,
                    final Long matchDerivationMilliseconds) {
        this.pFeatureCount = pFeatureCount;
        this.pFeatureDerivationMilliseconds = pFeatureDerivationMilliseconds;
        this.qFeatureCount = qFeatureCount;
        this.qFeatureDerivationMilliseconds = qFeatureDerivationMilliseconds;
        this.consensusSetSizes = consensusSetSizes;
        this.matchDerivationMilliseconds = matchDerivationMilliseconds;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<MatchTrialStats> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrialStats.class);

}
