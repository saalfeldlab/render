package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchAggregator;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.janelia.render.client.CopyMatchClient.Parameters.MatchAggregationScope.SAME_GROUP_ID_ONLY;

/**
 * Java client for copying matches from one collection to another.
 *
 * @author Eric Trautman
 */
public class CopyMatchClient {

    public static class Parameters extends CommandLineParameters {

        public enum MatchAggregationScope {
            ALL_GROUP_IDS, SAME_GROUP_ID_ONLY, DIFFERENT_GROUP_IDS_ONLY
        }

        @ParametersDelegate
        MatchWebServiceParameters matchClient = new MatchWebServiceParameters();

        @Parameter(
                names = "--toOwner",
                description = "Name of target collection owner (default is same as source stack owner)"
        )
        public String toOwner;

        @Parameter(
                names = "--toCollection",
                description = "Name of target collection",
                required = true)
        public String toCollection;

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

        String getToOwner() {
            if (toOwner == null) {
                toOwner = matchClient.owner;
            }
            return toOwner;
        }

        boolean isAggregationRequested() {
            return (maxMatchesPerPair != null) && (matchAggregationRadius != null);
        }

        void validate() {
            if (maxMatchesPerPair != null) {
                if (matchAggregationRadius == null) {
                    throw new IllegalArgumentException("--matchFilterRadius must be specified when --maxMatchesPerPair is specified");
                }
            } else if (matchAggregationRadius != null) {
                throw new IllegalArgumentException("--maxMatchesPerPair must be specified when --matchFilterRadius is specified");
            }
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyMatchClient client = new CopyMatchClient(parameters);
                client.copyMatches();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final RenderDataClient fromDataClient;
    private final RenderDataClient toDataClient;
    private final List<String> pGroupIds;

    private CopyMatchClient(final Parameters parameters) throws Exception {

        this.parameters = parameters;

        this.fromDataClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                   parameters.matchClient.owner,
                                                   parameters.matchClient.collection);

        this.toDataClient = new RenderDataClient(parameters.matchClient.baseDataUrl,
                                                 parameters.getToOwner(),
                                                 parameters.toCollection);

        if ((parameters.pGroupIds == null) || (parameters.pGroupIds.size() == 0)) {
            this.pGroupIds = fromDataClient.getMatchPGroupIds();
        } else {
            this.pGroupIds = parameters.pGroupIds;
        }

    }

    private void copyMatches()
            throws Exception {
        for (final String pGroupId : pGroupIds) {
            copyMatches(pGroupId);
        }
    }

    private void copyMatches(final String pGroupId) throws Exception {

        List<CanvasMatches> sourceMatches =
                fromDataClient.getMatchesWithPGroupId(pGroupId, false);

        if (sourceMatches.size() > 0) {

            if (parameters.removeExisting) {
                toDataClient.deleteMatchesWithPGroupId(pGroupId);
            }

            if (parameters.isAggregationRequested()) {
                sourceMatches = aggregateMatches(sourceMatches);
            }

            toDataClient.saveMatches(sourceMatches);

        } else {
            LOG.info("no matches found for pGroupId {} in {}", pGroupId, parameters.matchClient.collection);
        }
    }

    private List<CanvasMatches> aggregateMatches(List<CanvasMatches> sourceMatches) {
        final List<CanvasMatches> matchesToKeepAsIs;
        final List<CanvasMatches> matchesToAggregate;

        switch (parameters.matchAggregationScope) {
            case SAME_GROUP_ID_ONLY:
            case DIFFERENT_GROUP_IDS_ONLY:
                matchesToKeepAsIs = new ArrayList<>(sourceMatches.size());
                matchesToAggregate = new ArrayList<>(sourceMatches.size());
                final boolean aggregateSameGroups = parameters.matchAggregationScope.equals(SAME_GROUP_ID_ONLY);

                for (final CanvasMatches pair : sourceMatches) {
                    if (pair.getpGroupId().equals(pair.getqGroupId())) { // pair has same group id
                        if (aggregateSameGroups) {
                            matchesToAggregate.add(pair);
                        } else {
                            matchesToKeepAsIs.add(pair);
                        }
                    } else { // pair has different group id
                        if (aggregateSameGroups) {
                            matchesToKeepAsIs.add(pair);
                        } else {
                            matchesToAggregate.add(pair);
                        }
                    }
                }
                break;

            default:
                matchesToKeepAsIs = new ArrayList<>();
                matchesToAggregate = sourceMatches;
                break;
        }

        MatchAggregator.aggregateWithinRadius(matchesToAggregate,
                                              parameters.maxMatchesPerPair,
                                              parameters.matchAggregationRadius);

        sourceMatches = matchesToAggregate;
        sourceMatches.addAll(matchesToKeepAsIs);

        return sourceMatches;
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyMatchClient.class);
}
