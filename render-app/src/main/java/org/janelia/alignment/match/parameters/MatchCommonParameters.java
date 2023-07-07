package org.janelia.alignment.match.parameters;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

import java.io.Serializable;

/**
 * Common parameters for match derivation that don't have a better home.
 *
 * @author Eric Trautman
 */
@Parameters
public class MatchCommonParameters
        implements Serializable {

    @Parameter(
            names = "--maxPairsPerStackBatch",
            description = "Maximum number of pairs to include in stack-based batches")
    public int maxPairsPerStackBatch = 100;

    @ParametersDelegate
    public FeatureStorageParameters featureStorage = new FeatureStorageParameters();

    @Parameter(
            names = { "--gdMaxPeakCacheGb" },
            description = "Maximum number of gigabytes of peaks to cache")
    public Integer maxPeakCacheGb = 2;


    public MatchCommonParameters() {
    }

}