package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

import org.janelia.render.client.RenderDataClient;

/**
 * Parameters and methods for determining clusters of connected tiles within a layer.
 *
 * @author Eric Trautman
 */
public class TileClusterParameters
        implements Serializable {

    @Parameter(
            names = "--matchOwner",
            description = "Match collection owner (default is to use stack owner)")
    public String matchOwner;

    @Parameter(
            names = "--matchCollection",
            description = "Match collection name")
    public String matchCollection;

    @Parameter(
            names = "--maxSmallClusterSize",
            description = "If specified, small connected clusters with this many or fewer tiles will be " +
                          "considered unconnected and be removed.")
    public Integer maxSmallClusterSize;

    @Parameter(
            names = "--smallClusterFactor",
            description = "If specified, relatively small connected clusters will be considered unconnected " +
                          "and be removed.  A layer's max small cluster size is calculated by multiplying this " +
                          "factor by the size of the layer's largest connected cluster.  " +
                          "This value will be ignored if --maxSmallClusterSize is specified.")
    public Double smallClusterFactor;

    @Parameter(
            names = "--includeMatchesOutsideGroup",
            description = "When determining connected clusters, include outside group matches (e.g. for merged reacquired sections)",
            arity = 0)
    public boolean includeMatchesOutsideGroup = false;


    public void validate() {
        validate(false);
    }

    public void validate(final boolean isRequired)
            throws IllegalArgumentException {

        if (isRequired || isDefined()) {

            if (matchCollection == null) {
                throw new IllegalArgumentException(
                        "--matchCollection must be specified when " +
                        "--maxSmallClusterSize or --smallClusterFactor is specified");
            }

        } else if (matchCollection != null) {
            throw new IllegalArgumentException(
                    "--maxSmallClusterSize or --smallClusterFactor must be specified when " +
                    "--matchCollection is specified");
        }

    }

    public boolean isDefined() {
        return (maxSmallClusterSize != null) || (smallClusterFactor != null);
    }

    public int getEffectiveMaxSmallClusterSize(final int largestClusterSize) {
        int maxSize = 0;
        if (isDefined()) {
            maxSize = maxSmallClusterSize == null ?
                      (int) Math.ceil(largestClusterSize * smallClusterFactor) :
                      maxSmallClusterSize;
        }
        return maxSize;
    }

    public RenderDataClient getMatchDataClient(final String baseDataUrl, final String defaultOwner) {
        RenderDataClient client = null;
        if (matchCollection != null) {
            final String owner = matchOwner == null ? defaultOwner : matchOwner;
            client = new RenderDataClient(baseDataUrl, owner, matchCollection);
        }
        return client;
    }

}
