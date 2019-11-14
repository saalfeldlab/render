package org.janelia.render.client.cache;

import java.util.List;

import mpicbg.imglib.algorithm.scalespace.DifferenceOfGaussianPeak;
import mpicbg.imglib.type.numeric.real.FloatType;

/**
 * Cache container for a canvas' list of peaks.
 *
 * @author Eric Trautman
 */
public class CachedCanvasPeaks
        implements CachedCanvasData {

    private final List<DifferenceOfGaussianPeak<FloatType>> peakList;
    private final double[] clipOffsets;

    CachedCanvasPeaks(final List<DifferenceOfGaussianPeak<FloatType>> peakList,
                      final double[] clipOffsets) {
        this.peakList = peakList;
        this.clipOffsets = clipOffsets;
    }

    public List<DifferenceOfGaussianPeak<FloatType>> getPeakList() {
        return peakList;
    }

    public double[] getClipOffsets() {
        return clipOffsets;
    }

    public long getKilobytes() {
        return (long) (peakList.size() * AVERAGE_KILOBYTES_PER_PEAK) + 1;
    }

    @Override
    public String toString() {
        return "peakList[" + peakList.size() + "]";
    }

    /** Since peak lists are only in-memory, this method is a no-op. */
    public void remove() {
    }

    /** Average size of a peak. */
    private static final double AVERAGE_KILOBYTES_PER_PEAK = 0.05; // 50 bytes

}