package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.MultiSEMTileRemovalParameters;
import org.janelia.render.client.parameter.StackWithRemovalParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for removing tiles from multi-SEM stacks.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.MultiSEMTileRemovalClient}.
 *
 * <p>Removal operations are quick web service calls, so nothing is distributed to spark workers here.</p>
 *
 * @see org.janelia.render.client.multisem.MultiSEMTileRemovalClient
 *
 * @author Eric Trautman
 */
public class MultiSEMTileRemovalClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public MultiSEMTileRemovalParameters tileRemoval = new MultiSEMTileRemovalParameters();
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.tileRemoval.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                // apply the same removal parameters to each stack identified by the multiProject parameters
                final MultiProjectParameters multiProject = parameters.multiProject;
                final List<StackWithRemovalParameters> stackWithRemovalList =
                        multiProject.stackIdWithZ.getStackIdList(multiProject.getDataClient()).stream()
                                .map(stackId -> new StackWithRemovalParameters(stackId, parameters.tileRemoval))
                                .collect(Collectors.toList());

                // NOTE: no spark context is needed here because all removal is run on the driver
                final MultiSEMTileRemovalClient client = new MultiSEMTileRemovalClient();
                client.removeTiles(multiProject.getBaseDataUrl(), stackWithRemovalList);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public MultiSEMTileRemovalClient() {
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {

        final List<StackWithRemovalParameters> stackWithRemovalList =
                pipelineParameters.getTileRemovalList();

        AlignmentPipelineParameters.validateRequiredElementExists("tileRemovalList",
                                                                  stackWithRemovalList);

        if (stackWithRemovalList.isEmpty()) {
            throw new IllegalArgumentException("tileRemovalList must contain at least one element");
        }

        for (final StackWithRemovalParameters stackWithRemoval : stackWithRemovalList) {
            stackWithRemoval.validate();
        }
    }

    /** Run the client as part of an alignment pipeline. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        removeTiles(pipelineParameters.getMultiProject(null).getBaseDataUrl(),
                    pipelineParameters.getTileRemovalList());
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.REMOVE_TILES;
    }

    public void removeTiles(final String baseDataUrl,
                            final List<StackWithRemovalParameters> stackWithRemovalList)
            throws IOException {

        LOG.info("removeTiles: entry, processing {} stack(s)", stackWithRemovalList.size());

        final org.janelia.render.client.multisem.MultiSEMTileRemovalClient javaClient =
                new org.janelia.render.client.multisem.MultiSEMTileRemovalClient();

        // cache each owner's stack ids so that existence can be checked without repeating requests
        final Map<String, Set<StackId>> ownerToStackIds = new HashMap<>();

        for (final StackWithRemovalParameters stackWithRemoval : stackWithRemovalList) {

            final StackId stackId = stackWithRemoval.getStackId();
            final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                     stackId.getOwner(),
                                                                     stackId.getProject());

            Set<StackId> ownerStackIds = ownerToStackIds.get(stackId.getOwner());
            if (ownerStackIds == null) {
                ownerStackIds = new HashSet<>(dataClient.getOwnerStacks());
                ownerToStackIds.put(stackId.getOwner(), ownerStackIds);
            }

            if (ownerStackIds.contains(stackId)) {
                LOG.info("removeTiles: processing {}", stackId.toDevString());
                javaClient.removeTiles(dataClient, stackId.getStack(), stackWithRemoval.getTileRemoval());
            } else {
                LOG.info("removeTiles: skipping removal for {} because the stack does not exist",
                         stackId.toDevString());
            }
        }

        LOG.info("removeTiles: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiSEMTileRemovalClient.class);
}
