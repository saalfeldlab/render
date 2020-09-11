package org.janelia.alignment.match.stage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supports canvas pair match generation for a multi-stage matching process.
 *
 * @author Eric Trautman
 */
public class MultiStageMatcher
        implements Serializable {

    /**
     * Encapsulates canvas pair match generation results for all stages in a multi-stage matching process.
     */
    public static class PairResult
            implements Serializable {

        private final List<StageMatcher.PairResult> stagePairResultList;

        public PairResult() {
            this.stagePairResultList = new ArrayList<>();
        }

        public void addStagePairResult(final StageMatcher.PairResult stagePairResult) {
            stagePairResultList.add(stagePairResult);
        }

        public List<CanvasMatches> getCanvasMatchesList() {
            final int lastStageIndex = stagePairResultList.size() - 1;
            final List<CanvasMatches> list;
            if (lastStageIndex >= 0) {
                list = stagePairResultList.get(lastStageIndex).getCanvasMatchesList();
            } else {
                list = new ArrayList<>();
            }
            return list;
        }

        public List<StageMatcher.PairResult> getStagePairResultList() {
            return stagePairResultList;
        }
    }

    private final List<StageMatcher> stageMatcherList;

    public MultiStageMatcher(final List<StageMatcher> stageMatcherList) {
        this.stageMatcherList = stageMatcherList;
    }

    public PairResult generateMatchesForPair(final OrderedCanvasIdPair pair) {

        final PairResult multiStagePairResult = new PairResult();

        final CanvasId p = pair.getP();
        final CanvasId q = pair.getQ();

        CanvasIdWithRenderContext pFeatureContextCanvasId = null;
        CanvasIdWithRenderContext qFeatureContextCanvasId = null;
        CanvasIdWithRenderContext pPeakContextCanvasId = null;
        CanvasIdWithRenderContext qPeakContextCanvasId = null;

        for (final StageMatcher stageMatcher : stageMatcherList) {

            final StageMatchingResources stageResources = stageMatcher.getStageResources();

            if (stageResources.exceedsMaxNeighborDistance(pair.getAbsoluteDeltaZ())) {
                LOG.info("generateMultiStageMatchesForPair: skipping stage {} for pair {} with absoluteDeltaZ {}",
                         stageResources.getStageName(), pair, pair.getAbsoluteDeltaZ());
                break;
            }

            pFeatureContextCanvasId = stageResources.getFeatureContextCanvasId(p, pFeatureContextCanvasId);
            qFeatureContextCanvasId = stageResources.getFeatureContextCanvasId(q, qFeatureContextCanvasId);
            pPeakContextCanvasId = stageResources.getPeakContextCanvasId(p, pPeakContextCanvasId);
            qPeakContextCanvasId = stageResources.getPeakContextCanvasId(q, qPeakContextCanvasId);

            final StageMatcher.PairResult stagePairResult =
                    stageMatcher.generateStageMatchesForPair(pFeatureContextCanvasId,
                                                             qFeatureContextCanvasId,
                                                             pPeakContextCanvasId,
                                                             qPeakContextCanvasId);

            multiStagePairResult.addStagePairResult(stagePairResult);

            if (stagePairResult.size() > 0) {
                break;
            }
        }

        return multiStagePairResult;
    }

    public void logPairCountStats() {
        stageMatcherList.forEach(StageMatcher::logPairCountStats);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiStageMatcher.class);
}
