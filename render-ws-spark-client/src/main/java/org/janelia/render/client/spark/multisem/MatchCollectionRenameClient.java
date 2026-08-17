package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MatchCollectionRenameParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for renaming an owner's match collections (e.g. so that collection names stay derived from
 * the names of the stacks that later pipeline steps use).
 *
 * <p>Renames are quick web service calls, so nothing is distributed to spark workers here.</p>
 */
public class MatchCollectionRenameClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public MatchCollectionRenameParameters matchCollectionRename = new MatchCollectionRenameParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.matchCollectionRename.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                // NOTE: no spark context is needed here because all renames are run on the driver
                final MatchCollectionRenameClient client = new MatchCollectionRenameClient();
                client.renameMatchCollections(parameters.multiProject.getBaseDataUrl(),
                                              parameters.matchCollectionRename);
            }
        };
        clientRunner.run();
    }

    public MatchCollectionRenameClient() {
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        final MatchCollectionRenameParameters matchCollectionRename = pipelineParameters.getMatchCollectionRename();
        AlignmentPipelineParameters.validateRequiredElementExists("matchCollectionRename",
                                                                  matchCollectionRename);
        matchCollectionRename.validate();
    }

    /** Run the client as part of an alignment pipeline. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(null);

        renameMatchCollections(multiProject.getBaseDataUrl(),
                               pipelineParameters.getMatchCollectionRename());
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.RENAME_MATCH_COLLECTIONS;
    }

    public void renameMatchCollections(final String baseDataUrl,
                                       final MatchCollectionRenameParameters matchCollectionRename)
            throws IllegalArgumentException, IOException {

        LOG.info("renameMatchCollections: entry, matchCollectionRename={}", matchCollectionRename);

        final String owner = matchCollectionRename.matchCollectionOwner;
        final RenderDataClient ownerDataClient = new RenderDataClient(baseDataUrl, owner, "not_used");

        final List<String> existingNames = ownerDataClient.getOwnerMatchCollections().stream()
                .map(mcmd -> mcmd.getCollectionId().getName())
                .sorted()
                .collect(Collectors.toList());

        final Pattern sourcePattern = matchCollectionRename.buildSourceNamePattern();

        // sourceNames and targetNames are parallel lists, so targetNames.get(i) is the new name for sourceNames.get(i)
        final List<String> sourceNames = new ArrayList<>();
        final List<String> targetNames = new ArrayList<>();
        for (final String existingName : existingNames) {
            final Matcher matcher = sourcePattern.matcher(existingName);
            if (matcher.matches()) {
                sourceNames.add(existingName);
                targetNames.add(matcher.replaceFirst(matchCollectionRename.targetNamePattern));
            }
        }

        // check all renames before doing any of them so that partial renames are less likely
        final Set<String> existingNameSet = new HashSet<>(existingNames);
        final Set<String> distinctTargetNames = new HashSet<>();
        for (int i = 0; i < targetNames.size(); i++) {
            final String targetName = targetNames.get(i);
            if (existingNameSet.contains(targetName)) {
                throw new IllegalArgumentException("cannot rename " + sourceNames.get(i) + " to " + targetName +
                                                   " because a collection with that name already exists for owner " +
                                                   owner);
            }
            if (! distinctTargetNames.add(targetName)) {
                throw new IllegalArgumentException("cannot rename more than one collection to " + targetName +
                                                   " for owner " + owner);
            }
        }

        if (targetNames.isEmpty()) {
            LOG.warn("renameMatchCollections: none of the {} collection(s) for owner {} match the sourceNamePattern '{}'",
                     existingNames.size(), owner, matchCollectionRename.sourceNamePattern);
        }

        for (int i = 0; i < targetNames.size(); i++) {
            final String sourceName = sourceNames.get(i);
            final MatchCollectionId targetCollectionId = new MatchCollectionId(owner, targetNames.get(i));
            final RenderDataClient matchDataClient = new RenderDataClient(baseDataUrl, owner, sourceName);
            matchDataClient.renameMatchCollection(targetCollectionId);
        }

        LOG.info("renameMatchCollections: exit, renamed {} collection(s)", targetNames.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(MatchCollectionRenameClient.class);
}
