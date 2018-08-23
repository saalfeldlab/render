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

    private final Integer firstCanvasFeatureCount;
    private final Long firstCanvasFeatureDerivationMilliseconds;
    private final Integer secondCanvasFeatureCount;
    private final Long secondCanvasFeatureDerivationMilliseconds;
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

    MatchTrialStats(final Integer firstCanvasFeatureCount,
                    final Long firstCanvasFeatureDerivationMilliseconds,
                    final Integer secondCanvasFeatureCount,
                    final Long secondCanvasFeatureDerivationMilliseconds,
                    final List<Integer> consensusSetSizes,
                    final Long matchDerivationMilliseconds) {
        this.firstCanvasFeatureCount = firstCanvasFeatureCount;
        this.firstCanvasFeatureDerivationMilliseconds = firstCanvasFeatureDerivationMilliseconds;
        this.secondCanvasFeatureCount = secondCanvasFeatureCount;
        this.secondCanvasFeatureDerivationMilliseconds = secondCanvasFeatureDerivationMilliseconds;
        this.consensusSetSizes = consensusSetSizes;
        this.matchDerivationMilliseconds = matchDerivationMilliseconds;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    private static final JsonUtils.Helper<MatchTrialStats> JSON_HELPER =
            new JsonUtils.Helper<>(MatchTrialStats.class);

}
