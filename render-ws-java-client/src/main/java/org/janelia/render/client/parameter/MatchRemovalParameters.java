package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;

/**
 * Parameters for removing matches.
 */
@Parameters
public class MatchRemovalParameters
        implements Serializable {

    @Parameter(
            names = "--minCrossMatchPixelDistance",
            description = "Remove any cross match pair when all of the match points for that pair are offset less than this distance.")
    public Double minCrossMatchPixelDistance;

    public void validate()
            throws IllegalArgumentException {

        if ((minCrossMatchPixelDistance == null) || (minCrossMatchPixelDistance < 0.1)) {
            throw new IllegalArgumentException("--minCrossMatchPixelDistance must be specified and >= 0.1");
        }

    }

}