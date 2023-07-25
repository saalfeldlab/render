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
            names = "--maxMatchesPerPairSame",
            description = "If same layer match count is greater than this number, " +
                          "reduce them with filtering (omit to copy all matches)")
    public Integer maxMatchesPerPairSame;

    @Parameter(
            names = "--matchAggregationRadiusSame",
            description = "Pixel radius for same layer match filtering")
    public Double matchAggregationRadiusSame;

    @Parameter(
            names = "--maxMatchesPerPairCross",
            description = "If cross layer match count is greater than this number, " +
                          "reduce them with filtering (omit to copy all matches)")
    public Integer maxMatchesPerPairCross;

    @Parameter(
            names = "--matchAggregationRadiusCross",
            description = "Pixel radius for cross layer match filtering")
    public Double matchAggregationRadiusCross;

    public MatchCopyParameters() {
    }

    public boolean isSameLayerAggregationRequested() {
        return (maxMatchesPerPairSame != null) && (matchAggregationRadiusSame != null);
    }

    public boolean isCrossLayerAggregationRequested() {
        return (maxMatchesPerPairCross != null) && (matchAggregationRadiusCross != null);
    }

    public boolean isAggregationRequested() {
        return isSameLayerAggregationRequested() || isCrossLayerAggregationRequested();
    }

    public void validate()
            throws IllegalArgumentException {

        if ((toCollection != null) && (toCollectionSuffix != null)) {
            throw new IllegalArgumentException("specify --toCollection or --toCollectionSuffix but not both");
        }

        if (maxMatchesPerPairSame != null) {
            if (matchAggregationRadiusSame == null) {
                throw new IllegalArgumentException("--matchAggregationRadiusSame must be specified when --maxMatchesPerPairSame is specified");
            }
        } else if (matchAggregationRadiusSame != null) {
            throw new IllegalArgumentException("--maxMatchesPerPairSame must be specified when --matchAggregationRadiusSame is specified");
        }

        if (maxMatchesPerPairCross != null) {
            if (matchAggregationRadiusCross == null) {
                throw new IllegalArgumentException("--matchAggregationRadiusCross must be specified when --maxMatchesPerPairCross is specified");
            }
        } else if (matchAggregationRadiusCross != null) {
            throw new IllegalArgumentException("--maxMatchesPerPairCross must be specified when --matchAggregationRadiusCross is specified");
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