package org.janelia.render.client.parameter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.Serializable;
import java.util.List;

import org.janelia.render.client.RenderDataClient;

/**
 * Parameters for copying matches.
 *
 * @author Eric Trautman
 */
@Parameters
public class MatchCopyParameters
        implements Serializable {

    public enum MatchAggregationScope {
        ALL_GROUP_IDS, SAME_GROUP_ID_ONLY, DIFFERENT_GROUP_IDS_ONLY
    }

    @Parameter(
            names = "--matchCopyPhaseName",
            description = "Name for this phase within a multi-phase copy process (use default name for simple copies)"
    )
    public String matchCopyPhaseName = "primary";

    @Parameter(
            names = "--toOwner",
            description = "Name of target collection owner (default is same as source collection owner)"
    )
    public String toOwner;

    @Parameter(
            names = "--toCollection",
            description = "Name of target collection " +
                          "(omit to copy back to source collection when aggregating matches)")
    public String toCollection;

    @Parameter(
            names = "--toCollectionSuffix",
            description = "Derive target collection name by appending this suffix to the source collection name")
    public String toCollectionSuffix;

    @Parameter(
            names = "--pGroupId",
            description = "pGroupId to be copied (omit to copy all groups)",
            variableArity = true)
    public List<String> pGroupIds;

    @Parameter(
            names = "--removeExisting",
            description = "Remove any existing target matches with the specified pGroupId (default is to keep them)",
            arity = 0)
    public boolean removeExisting = false;

    @Parameter(
            names = "--maxMatchesPerPair",
            description = "If match count is greater than this number, " +
                          "reduce them with filtering (omit to copy all matches)")
    public Integer maxMatchesPerPair;

    @Parameter(
            names = "--matchAggregationRadius",
            description = "Pixel radius for match filtering")
    public Double matchAggregationRadius;

    @Parameter(
            names = "--matchAggregationScope",
            description = "Identifies which match pairs to aggregate")
    public MatchAggregationScope matchAggregationScope = MatchAggregationScope.ALL_GROUP_IDS;

    public MatchCopyParameters() {
    }

    public boolean isAggregationRequested() {
        return (maxMatchesPerPair != null) && (matchAggregationRadius != null);
    }

    public void validate()
            throws IllegalArgumentException {

        if ((toCollection != null) && (toCollectionSuffix != null)) {
            throw new IllegalArgumentException("specify --toCollection or --toCollectionSuffix but not both");
        }

        if (maxMatchesPerPair != null) {
            if (matchAggregationRadius == null) {
                throw new IllegalArgumentException("--matchFilterRadius must be specified when --maxMatchesPerPair is specified");
            }
        } else if (matchAggregationRadius != null) {
            throw new IllegalArgumentException("--maxMatchesPerPair must be specified when --matchFilterRadius is specified");
        }

        if ((toCollection == null) && (toCollectionSuffix == null) && (! isAggregationRequested())) {
            throw new IllegalArgumentException("must specify --toCollection or --toCollectionSuffix if you are not aggregating matches");
        }

    }

    public RenderDataClient buildTargetMatchClient(final RenderDataClient sourceMatchClient) {
        final String owner = toOwner == null ? sourceMatchClient.getOwner() : toOwner;
        final String collectionName;
        if (toCollectionSuffix != null) {
            collectionName = sourceMatchClient.getProject() + toCollectionSuffix;
        } else if (toCollection != null) {
            collectionName = toCollection;
        } else {
            collectionName = sourceMatchClient.getProject();
        }
        return sourceMatchClient.buildClient(owner, collectionName);
    }

}