package org.janelia.alignment.util;

/**
 * Utility to track process time intervals.
 *
 * @author Eric Trautman
 */
public class ProcessTimer {

    public static final long DEFAULT_INTERVAL = 5000;

    private long interval;
    private long start;
    private long lastIntervalStart;

    public ProcessTimer() {
        this(DEFAULT_INTERVAL);
    }

    public ProcessTimer(long interval) {
        this.interval = interval;
        this.start = System.currentTimeMillis();
        this.lastIntervalStart = this.start;
    }

    public boolean hasIntervalPassed() {
        boolean hasPassed = ((System.currentTimeMillis() - lastIntervalStart) > interval);
        if (hasPassed) {
            lastIntervalStart = System.currentTimeMillis();
        }
        return hasPassed;
    }

    public long getElapsedMilliseconds() {
        return System.currentTimeMillis() - start;
    }

    public long getElapsedSeconds() {
        return getElapsedMilliseconds() / 1000;
    }

}
