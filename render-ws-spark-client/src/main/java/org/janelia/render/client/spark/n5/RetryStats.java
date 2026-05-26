package org.janelia.render.client.spark.n5;

import java.io.Serializable;

/**
 * Statistics collected during a retry operation.
 * Tracks retry counts and wait times for analysis.
 */
public class RetryStats
        implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String operationDescription;
    private final int retryCount;           // Number of retries performed (not including initial attempt)
    private final long totalWaitTimeMs;     // Sum of all wait times (initial jitter + retry delays)
    private final long initialJitterMs;     // Initial jitter delay before first attempt

    public RetryStats(
            final String operationDescription,
            final int retryCount,
            final long totalWaitTimeMs,
            final long initialJitterMs) {
        this.operationDescription = operationDescription;
        this.retryCount = retryCount;
        this.totalWaitTimeMs = totalWaitTimeMs;
        this.initialJitterMs = initialJitterMs;
    }

    @Override
    public String toString() {
        return String.format("RetryStats[op=%s, retries=%d, totalWait=%dms, jitter=%dms]",
                             operationDescription, retryCount, totalWaitTimeMs, initialJitterMs);
    }

}
