package org.janelia.alignment.match.stage;

import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains categorized counts for all canvas pairs matched in a specific stage.
 * This class is NOT thread safe.
 *
 * @author Eric Trautman
 */
public class StageMatchPairCounts
        implements Serializable {

    private long siftPoorCoverage = 0;
    private long siftPoorQuantity = 0;
    private long siftSaved = 0;
    private long combinedPoorCoverage = 0;
    private long combinedPoorQuantity = 0;
    private long combinedSaved = 0;

    public long getTotalSaved() {
        return siftSaved + combinedSaved;
    }

    public long getTotalProcessed() {
        return siftPoorCoverage + siftPoorQuantity + siftSaved +
               combinedPoorCoverage + combinedPoorQuantity + combinedSaved;
    }

    public void incrementSiftPoorCoverage() {
        this.siftPoorCoverage++;
    }

    public void incrementSiftPoorQuantity() {
        this.siftPoorQuantity++;
    }

    public void incrementSiftSaved() {
        siftSaved++;
    }

    public void incrementCombinedPoorCoverage() {
        this.combinedPoorCoverage++;
    }

    public void incrementCombinedPoorQuantity() {
        this.combinedPoorQuantity++;
    }

    public void incrementCombinedSaved() {
        this.combinedSaved++;
    }

    public void logStats(final String stageName) {
        final int percentSaved = (int) ((getTotalSaved() / (double) getTotalProcessed()) * 100);
        LOG.info("logStats: for stage {}, saved matches for {} out of {} pairs ({}%), siftPoorCoverage: {}, siftPoorQuantity: {}, siftSaved: {}, combinedPoorCoverage: {}, combinedPoorQuantity: {}, combinedSaved: {}, ",
                 stageName,
                 getTotalSaved(),
                 getTotalProcessed(),
                 percentSaved,
                 siftPoorCoverage,
                 siftPoorQuantity,
                 siftSaved,
                 combinedPoorCoverage,
                 combinedPoorQuantity,
                 combinedSaved);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StageMatchPairCounts.class);
}
