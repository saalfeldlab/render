package org.janelia.alignment.util;

/**
 * Utility to track process time intervals.
 *
 * @author Eric Trautman
 */
public class ProcessTimer {

    public static final long DEFAULT_INTERVAL = 5000;

    private final long interval;
    private final long start;
    private long lastIntervalStart;

    public ProcessTimer() {
        this(DEFAULT_INTERVAL);
    }

    public ProcessTimer(final long interval) {
        this.interval = interval;
        this.start = System.currentTimeMillis();
        this.lastIntervalStart = this.start;
    }

    public boolean hasIntervalPassed() {
        final boolean hasPassed = ((System.currentTimeMillis() - lastIntervalStart) > interval);
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

    @Override
    public String toString() {
        final long totalSeconds = getElapsedSeconds();
        final long totalMinutes = totalSeconds / 60;
        final long hours = totalMinutes / 60;
        final long minutes = totalMinutes % 60;
        final long seconds = totalSeconds % 60;
        return hours + " hours, " + minutes + " minutes, " + seconds + " seconds";
    }
}
