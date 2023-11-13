package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;

import java.io.Serializable;

/**
 * Parameters and methods for determining clusters of connected tiles within a layer.
 *
 * @author Eric Trautman
 */
public class TileClusterParameters
        implements Serializable {

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

    @Parameter(
            names = "--maxLayersPerBatch",
            description = "Maximum number of adjacent layers to connect at one time.  " +
                          "Larger connected stacks will use too much memory and/or recurse too deeply.  " +
                          "This option (in conjunction with --maxOverlapLayers) allows processing to be divided " +
                          "into smaller batches.")
    public Integer maxLayersPerBatch = 1000;

    @Parameter(
            names = "--maxOverlapLayers",
            description = "Maximum number of adjacent layers for matched tiles " +
                          "(ensures connections can be tracked across batches for large runs)")
    public Integer maxOverlapLayers = 10;

    @Parameter(
            names = "--maxLayersForUnconnectedEdge",
            description = "Maximum allowed number of consecutive z layers with an unconnected edge " +
                          "before flagging problem region (e.g. 50).  " +
                          "Omit to skip check for unconnected tile edges.")
    public Integer maxLayersForUnconnectedEdge;

    /**
     * Validate that --matchCollection has been defined if it is required
     * or if --maxSmallClusterSize or --smallClusterFactor has been defined.
     *
     * @throws IllegalArgumentException
     *   if parameters are inconsistently defined.
     */
    public void validateMatchCollection(final String matchCollection,
                                        final boolean isRequired)
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

}
