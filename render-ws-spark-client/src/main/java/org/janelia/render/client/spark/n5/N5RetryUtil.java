package org.janelia.render.client.spark.n5;

import java.io.Serializable;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for retry logic with exponential backoff and jitter.
 * Specifically designed for handling GCS rate limits and other transient failures.
 */
public class N5RetryUtil {

    public static class RetryParameters implements Serializable {

        /** Maximum number of retry attempts (beyond initial attempt). */
        private final int maxRetries;

        /** Initial delay in milliseconds before first retry. */
        private final long delayMs;

        /** Exponential backoff multiplier for delays. */
        private final double backoff;

        /** Maximum random delay in milliseconds before first attempt. */
        private final long startupJitterMs;

        public RetryParameters() {
            this(3, 2000, 2.0, 10_000);
        }

        public RetryParameters(final int maxRetries,
                               final long delayMs,
                               final double backoff,
                               final long startupJitterMs) {

            this.maxRetries = maxRetries;
            this.delayMs = delayMs;
            this.backoff = backoff;
            this.startupJitterMs = startupJitterMs;
        }

        @Override
        public String toString() {
            return "{maxRetries=" + maxRetries + ", delayMs=" + delayMs + ", backoff=" + backoff + ", startupJitterMs=" + startupJitterMs + '}';
        }
    }

    public static class RetryResultAndStats<T> {

        private final T result;
        private final RetryStats stats;

        public RetryResultAndStats(final T result,
                                   final RetryStats stats) {
            this.result = result;
            this.stats = stats;
        }

        @SuppressWarnings("unused")
        public T getResult() {
            return result;
        }

        public RetryStats getStats() {
            return stats;
        }
    }

    /**
     * Functional interface for operations that can throw exceptions.
     * Extends Serializable to support Spark's distributed execution.
     */
    @FunctionalInterface
    public interface RunnableWithException extends Serializable {
        void run() throws Exception;
    }


    /**
     * Execute an operation with exponential backoff retry logic.
     * Specifically handles GCS rate limit errors with delays, and retries other errors without delay.
     *
     * @param  operation             the operation to execute.
     * @param  parameters            retry parameters.
     * @param  operationDescription  description of operation for logging.
     * @return the result and retry statistics
     * @throws Exception if all retries are exhausted
     */
    public static <T> RetryResultAndStats<T> executeWithRetry(
            final Supplier<T> operation,
            final RetryParameters parameters,
            final String operationDescription) throws Exception {

        // Track statistics
        long initialJitterMs = 0;
        long totalWaitTimeMs = 0;

        // Add initial random delay to space out task execution (0 to startupJitterMs)
        if (parameters.startupJitterMs > 0)
        {
            initialJitterMs = (long)(Math.random() * parameters.startupJitterMs);
            LOG.info("executeWithRetry: Initial jitter for {}, delaying first attempt by {}ms (max: {}ms)",
                     operationDescription, initialJitterMs, parameters.startupJitterMs);
            Thread.sleep(initialJitterMs);
            totalWaitTimeMs += initialJitterMs;
        }
        else
        {
            LOG.info("executeWithRetry: NO initial jitter for {}", operationDescription);
        }

        Exception lastException = null;
        long delayMs = parameters.delayMs;
        int actualRetries = 0;

        for (int attempt = 0; attempt <= parameters.maxRetries; attempt++) {
            final String context = operationDescription + " (attempt " + (attempt+1) + "/" + (parameters.maxRetries+1) + ")";
            try {
                final T result = operation.get();
                final RetryStats stats = new RetryStats(
                        operationDescription,
                        actualRetries,
                        totalWaitTimeMs,
                        initialJitterMs);
                return new RetryResultAndStats<>(result, stats);
            } catch (final Exception e) {

                LOG.warn("executeWithRetry: caught exception in {}, attempt={}, maxRetries={}",
                         context, attempt, parameters.maxRetries, e);

                lastException = e;

                // Check if it's a GCS rate limit error
                final boolean isRateLimitError = e.getMessage() != null &&
                                                 (e.getMessage().contains("GCS429") ||
                                                  e.getMessage().contains("rate limit") ||
                                                  e.getMessage().contains("StorageException"));

                if (isRateLimitError && attempt < parameters.maxRetries) {
                    // Add jitter: randomize delay between 50% and 150% of calculated value
                    // This prevents thundering herd where all workers retry at similar intervals
                    final long jitteredDelay = (long)(delayMs * (0.5 + Math.random()));

                    LOG.info("executeWithRetry: GCS rate limit hit for {}, retrying in {}ms (jittered from {}ms)",
                             context, jitteredDelay, delayMs);

                    Thread.sleep(jitteredDelay);
                    totalWaitTimeMs += jitteredDelay;
                    delayMs = (long)(delayMs * parameters.backoff);
                    actualRetries++;
                } else if (attempt < parameters.maxRetries) {
                    // For non-rate-limit errors, retry without delay
                    actualRetries++;
                }
            }
        }

        // All retries exhausted - fail fast
        throw new RuntimeException(
                "Failed " + operationDescription + " after " + (parameters.maxRetries+1) + " attempts",
                lastException);
    }


    /**
     * Execute a void operation with exponential backoff retry logic.
     *
     * @param  operation   The operation to execute.
     * @param  parameters  retry parameters.
     * @throws Exception if all retries are exhausted
     */
    public static RetryStats executeWithRetryVoid(
            final RunnableWithException operation,
            final RetryParameters parameters,
            final String operationDescription) throws Exception {

        final RetryResultAndStats<Void> result = executeWithRetry(
                () -> {
                    try {
                        operation.run();
                        return null;
                    } catch (final Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                parameters,
                operationDescription);

        return result.stats;
    }

    private static final Logger LOG = LoggerFactory.getLogger(N5RetryUtil.class);

}
