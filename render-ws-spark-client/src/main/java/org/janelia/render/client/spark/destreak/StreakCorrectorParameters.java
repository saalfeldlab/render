package org.janelia.render.client.spark.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import org.janelia.alignment.destreak.SecondChannelStreakCorrector;

import java.io.Serializable;

/**
 * Parameters for the {@link org.janelia.alignment.destreak.SecondChannelStreakCorrector}.
 */
@Parameters
public class StreakCorrectorParameters implements Serializable {

    @Parameter(
            names = "--threshold",
            description = "Threshold parameter for streak correction (default: 100.0)")
    public double threshold = 100.0;

    @Parameter(
            names = "--blurRadius",
            description = "Blur radius for streak correction (default: 5)")
    public int blurRadius = 5;

    @Parameter(
            names = "--secondChannelBaseWeight",
            description = "Base weight for second channel in streak correction (default: 0.2)")
    public float secondChannelBaseWeight = 0.2f;

    @Parameter(
            names = "--numThreads",
            description = "Number of threads for streak correction processing (default: 1)")
    public int numThreads = 1;

    public StreakCorrectorParameters() {}

    public StreakCorrectorParameters(final double threshold,
                                   final int blurRadius,
                                   final float secondChannelBaseWeight,
                                   final int numThreads) {
        this.threshold = threshold;
        this.blurRadius = blurRadius;
        this.secondChannelBaseWeight = secondChannelBaseWeight;
        this.numThreads = numThreads;
    }

    @Override
    public String toString() {
        return "StreakCorrectorParameters{" +
               "threshold=" + threshold +
               ", blurRadius=" + blurRadius +
               ", secondChannelBaseWeight=" + secondChannelBaseWeight +
               ", numThreads=" + numThreads +
               '}';
    }

    public SecondChannelStreakCorrector getCorrector() {
        return new SecondChannelStreakCorrector(threshold, blurRadius, secondChannelBaseWeight, numThreads);
    }
}
