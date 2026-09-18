package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.StackRenameParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for renaming an owner's stacks (e.g. so that stack names reflect the processing
 * that later pipeline steps expect).
 *
 * <p>Each stack keeps its project, so only the stack name is matched and replaced.</p>
 *
 * <p>Renames are quick web service calls, so nothing is distributed to spark workers here.</p>
 */
public class StackRenameClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public StackRenameParameters stackRename = new StackRenameParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.stackRename.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                // NOTE: no spark context is needed here because all renames are run on the driver
                final StackRenameClient client = new StackRenameClient();
                client.renameStacks(parameters.multiProject.getBaseDataUrl(),
                                    parameters.stackRename);
            }
        };
        clientRunner.run();
    }

    public StackRenameClient() {
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        final StackRenameParameters stackRename = pipelineParameters.getStackRename();
        AlignmentPipelineParameters.validateRequiredElementExists("stackRename",
                                                                  stackRename);
        stackRename.validate();
    }

    /** Runs the {@link org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId#RENAME_STACKS RENAME_STACKS} step. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(null);

        renameStacks(multiProject.getBaseDataUrl(),
                     pipelineParameters.getStackRename());
    }

    public void renameStacks(final String baseDataUrl,
                             final StackRenameParameters stackRename)
            throws IllegalArgumentException, IOException {

        LOG.info("renameStacks: entry, stackRename={}", stackRename);

        final String owner = stackRename.stackOwner;
        final RenderDataClient ownerDataClient = new RenderDataClient(baseDataUrl, owner, "not_used");

        final List<StackId> existingStackIds = ownerDataClient.getOwnerStacks();

        final Pattern sourcePattern = stackRename.buildSourceNamePattern();

        // sourceStackIds and targetStackIds are parallel lists,
        // so targetStackIds.get(i) is the new id for sourceStackIds.get(i)
        final List<StackId> sourceStackIds = new ArrayList<>();
        final List<StackId> targetStackIds = new ArrayList<>();
        for (final StackId existingStackId : existingStackIds) {
            final Matcher matcher = sourcePattern.matcher(existingStackId.getStack());
            if (matcher.matches()) {
                sourceStackIds.add(existingStackId);
                // the project is kept so that only the stack name changes
                targetStackIds.add(new StackId(owner,
                                               existingStackId.getProject(),
                                               matcher.replaceFirst(stackRename.targetNamePattern)));
            }
        }

        // Check all renames before doing any of them so that partial renames are less likely.
        // Stack names only need to be distinct within a project, so these checks are per project.
        final Set<StackId> existingStackIdSet = new HashSet<>(existingStackIds);
        final Map<String, Set<String>> projectToTargetNames = new HashMap<>();
        for (int i = 0; i < targetStackIds.size(); i++) {
            final StackId targetStackId = targetStackIds.get(i);
            if (existingStackIdSet.contains(targetStackId)) {
                throw new IllegalArgumentException("cannot rename " + sourceStackIds.get(i).getStack() + " to " +
                                                   targetStackId.getStack() + " because a stack with that name " +
                                                   "already exists in project " + targetStackId.getProject() +
                                                   " for owner " + owner);
            }
            final Set<String> targetNamesForProject =
                    projectToTargetNames.computeIfAbsent(targetStackId.getProject(), p -> new HashSet<>());
            if (! targetNamesForProject.add(targetStackId.getStack())) {
                throw new IllegalArgumentException("cannot rename more than one stack to " +
                                                   targetStackId.getStack() + " in project " +
                                                   targetStackId.getProject() + " for owner " + owner);
            }
        }

        if (targetStackIds.isEmpty()) {
            LOG.warn("renameStacks: none of the {} stack(s) for owner {} match the sourceNamePattern '{}'",
                     existingStackIds.size(), owner, stackRename.sourceNamePattern);
        }

        for (int i = 0; i < targetStackIds.size(); i++) {
            final StackId sourceStackId = sourceStackIds.get(i);
            final StackId targetStackId = targetStackIds.get(i);
            // the rename request goes to the source stack's project
            final RenderDataClient stackDataClient = new RenderDataClient(baseDataUrl,
                                                                          owner,
                                                                          sourceStackId.getProject());
            stackDataClient.renameStack(sourceStackId.getStack(), targetStackId);
        }

        LOG.info("renameStacks: exit, renamed {} stack(s)", targetStackIds.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackRenameClient.class);
}
