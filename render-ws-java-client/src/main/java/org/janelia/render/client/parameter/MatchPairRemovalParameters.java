package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

/**
 * Parameters for removing match pairs.
 */
@Parameters
public class MatchPairRemovalParameters
        implements Serializable {

    @Parameter(
            names = "--minCrossMatchPixelDistance",
            description = "Remove any cross match pair that has match points with an average offset that is less than this distance.")
    public Double minCrossMatchPixelDistance;

    @Parameter(
            names = "--maxNumberOfPairsToRemove",
            description = "Throw an exception if more than this number of pairs will be removed.")
    public Integer maxNumberOfPairsToRemove;

    public void validate()
            throws IllegalArgumentException {

        if (minCrossMatchPixelDistance != null) {

            if (minCrossMatchPixelDistance < 0.1) {
                throw new IllegalArgumentException("when specified, --minCrossMatchPixelDistance must be >= 0.1");
            }

            if ((maxNumberOfPairsToRemove == null) || (maxNumberOfPairsToRemove < 1))
                throw new IllegalArgumentException(
                        "when --minCrossMatchPixelDistance is specified, --maxNumberOfPairsToRemove " +
                        "must be specified as a value 1 or greater"); {
            }

        }

    }

}