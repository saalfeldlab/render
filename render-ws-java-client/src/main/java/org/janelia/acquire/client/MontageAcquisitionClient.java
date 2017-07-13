package org.janelia.acquire.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParametersWithValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that montages a stack
 *
 * @author Cristian Goina (based on Eric Trautman's LowLatencyMontageClient)
 */
public class MontageAcquisitionClient {

    @SuppressWarnings("ALL")
    static class Parameters extends RenderDataClientParametersWithValidator {

        // NOTE: --baseDataUrl, --owner, --project, --validatorClass, and --validatorData parameters defined in RenderDataClientParameters
        // NOTE: --validatorClass and --validatorData parameters defined in RenderDataClientParametersWithValidator

        @Parameter(names = "--stackId", description = "identifier of the stack to be processed", required = false)
        private String stackId;

        @Parameter(names = "--stackFilter", description = "Regex the must be satisified by the stacks that need to be processed")
        private String stackFilter = "\\d+_acquire";

        @Parameter(names = "--aggregatedAcquireStack", description = "The name of the aggregated acquire stack", required = true)
        private String aggregatedAcquireStack = null;

        @Parameter(names = "--targetOwner", description = "owner of the montaged collection", required = false)
        private String targetOwner = null;

        @Parameter(names = "--targetProject", description = "project of the montaged collection", required = false)
        private String targetProject = null;

        @Parameter(names = "--targetStack", description = "stack of the montaged collection", required = false)
        private String targetStack = null;

        @Parameter(names = "--montageScript", description = "Full path of the montage generator script (e.g. /groups/flyTEM/.../montage_section_SL)", required = false)
        private String montageScript;

        @Parameter(names = "--montageParametersFile", description = "File containing JSON parameters for montage generation", required = false)
        private String montageParametersFile;

        @Parameter(names = "--montageWorkDirectory", description = "Parent directory for montage input data files (e.g. /nobackup/flyTEM/montage_work)", required = false)
        private String montageWorkDirectory;

        @Parameter(names = "--waitSeconds", description = "Number of seconds to wait before checking for newly acquired tiles (default 5)", required = false)
        private int waitSeconds = 5;
        @Parameter(names = "--doNotAggregateSources", arity = 0, description = "If the parameter is true it leaves the source collection as is and it will not aggregate it into a bigger collection", required = false)
        Boolean doNotAggregateSources;

        Boolean aggregateSources() {
            return !doNotAggregateSources;
        }

        public void validate()
                throws IllegalArgumentException {

            if (montageParametersFile == null) {
                throw new IllegalArgumentException("montageParametersFile must be specified");
            } else if (montageWorkDirectory == null) {
                throw new IllegalArgumentException("montageWorkDirectory must be specified");
            }
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, MontageAcquisitionClient.class);
                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.validate();

                final MontageAcquisitionClient client = new MontageAcquisitionClient(parameters);

                while (true) {
                    try {
                        client.processStacks();
                        if (client.hasFailedProcesses()) {
                            LOG.error("There are failed montages: {}", client.failedStackIds);
                        }
                        if (parameters.stackId != null && parameters.stackId.trim().length() > 0) {
                            // break if a single stack needed to be processed
                            break;
                        }
                        Thread.sleep(parameters.waitSeconds * 1000);
                    } catch (final Exception e) {
                        LOG.error("Process stacks failure", e);
                    }
                }

            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final Set<String> failedStackIds;
    private final RenderDataClient renderDataClient;

    public MontageAcquisitionClient(final Parameters parameters)
            throws IOException {
        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                parameters.owner,
                parameters.project);
        this.parameters = parameters;
        this.failedStackIds = new HashSet<>();
    }

    public boolean hasFailedProcesses() {
        return failedStackIds.size() > 0;
    }

    /**
     * Retrieves current list of unprocessed stacks and processes them sequentially.
     * The stacks are looked up for the specified user and project and they must have the following pattern "\\d+_acquire"
     * The ids of all acquisitions that fail processing are tracked so that we do not attempt to reprocess them.
     */
    public void processStacks()
            throws Exception {
        final List<StackId> stacksToProcess = retrieveStackIds(parameters);
        stacksToProcess.forEach(s -> {
            try {
                processStack(s);
            } catch (final Exception e) {
                LOG.error("Error processing stack {}", s, e);
                failedStackIds.add(s.getStack());
            }
        });
    }

    public List<StackId> retrieveStackIds(final MontageAcquisitionClient.Parameters stackFilter) throws IOException {
        final List<StackId> stackIds = renderDataClient.getOwnerStacks();
        return stackIds.stream()
                .filter(s -> s.getProject().equals(stackFilter.project) && s.getStack().matches(stackFilter.stackFilter))
                .filter(s -> stackFilter.stackId == null || s.getStack().equals(stackFilter.stackId))
                .collect(Collectors.toList());
    }

    /**
     * Process the specified acquisition.
     * If processing fails, the problem is logged and the acquisition id is tracked but no exception is thrown.
     */
    public void processStack(final StackId stackId) throws IOException {
        LOG.info("Process stack {}", stackId);
        final MontageParameters montageParameters = MontageParameters.load(parameters.montageParametersFile);

        montageParameters.setSourceCollection(
                new AlignmentRenderCollection(parameters.baseDataUrl, parameters.owner, parameters.project, stackId.getStack()));
        if (parameters.targetOwner != null && parameters.targetOwner.trim().length() > 0) {
            montageParameters.getTargetCollection().setOwner(parameters.targetOwner.trim());
        }
        if (parameters.targetProject != null && parameters.targetProject.trim().length() > 0) {
            montageParameters.getTargetCollection().setProject(parameters.targetProject.trim());
        }
        String targetStack;
        if (parameters.targetStack != null && parameters.targetStack.trim().length() > 0) {
            targetStack = parameters.targetStack.trim();
            montageParameters.getTargetCollection().setStack(targetStack);
        } else {
            targetStack = montageParameters.getTargetCollection().getStack();
        }
        if (targetStack == null || targetStack.trim().length() == 0) {
            targetStack = stackId.getStack().replace("acquire", "montage");
            montageParameters.getTargetCollection().setStack(targetStack);
        }

        final File montageWorkStackDir = Paths.get(parameters.montageWorkDirectory,
                parameters.project,
                stackId.getStack()).toAbsolutePath().toFile();

        if (! montageWorkStackDir.exists()) {
            if (! montageWorkStackDir.mkdirs()) {
                // check for existence again in case another parallel process already created the directory
                if (! montageWorkStackDir.exists()) {
                    throw new IOException("failed to create " + montageWorkStackDir.getAbsolutePath());
                }
            }
        }
        final List<Double> stackZValues = renderDataClient.getStackZValues(stackId.getStack());
        stackZValues.forEach(z -> {
            try {
                montageParameters.setSectionNumber(z);

                final String fileName = String.format("montage_input_%08.1f.json", z);
                final File montageInputFile = new File(montageWorkStackDir, fileName);
                LOG.info("Saving montage parameters to {}", montageInputFile);
                montageParameters.save(montageInputFile.getAbsolutePath());

                final ProcessBuilder processBuilder =
                        new ProcessBuilder(parameters.montageScript,
                                montageInputFile.getAbsolutePath()).inheritIO();

                LOG.info("invokeMontageProcessor: running {}", processBuilder.command());

                final Process process = processBuilder.start();
                final int montageReturnCode = process.waitFor();

                if (montageReturnCode != 0) {
                    LOG.error("invokeMontageProcessor: code {} returned", montageReturnCode);
                    throw new IllegalStateException("Montage error");
                } else {
                    LOG.info("invokeMontageProcessor: code {} returned", montageReturnCode);
                }
                if (parameters.aggregateSources()) {
                    // move the source stack into the aggregated stack
                    LOG.info("Retrieve tiles from {} for z {}", stackId, z);
                    final ResolvedTileSpecCollection sourceTiles = renderDataClient.getResolvedTiles(stackId.getStack(), z);
                    LOG.info("Copy tiles from {} for z {} to {}", stackId, z, parameters.aggregatedAcquireStack);
                    ensureAcquireStackExists(renderDataClient, parameters.aggregatedAcquireStack);
                    renderDataClient.saveResolvedTiles(sourceTiles, parameters.aggregatedAcquireStack, z);
                }
            } catch (final Exception e) {
                LOG.error("Error while saving the montage parameters for {}:{}", stackId, z, e);
                failedStackIds.add(stackId.getStack());
            }
        });
        if (parameters.aggregateSources() && !failedStackIds.contains(stackId.getStack())) {
            // remove the source stack now
            renderDataClient.deleteStack(stackId.getStack(), null);
            // complete the aggregated acquire stack
            renderDataClient.setStackState(parameters.aggregatedAcquireStack, StackMetaData.StackState.COMPLETE);
        }
    }

    public void ensureAcquireStackExists(final RenderDataClient renderDataClient, final String acquireStackName)
            throws Exception {
        LOG.info("ensureAcquireStackExists: entry {}", acquireStackName);

        StackMetaData stackMetaData;
        try {
            stackMetaData = renderDataClient.getStackMetaData(acquireStackName);
            if (!stackMetaData.isLoading()) {
                renderDataClient.setStackState(acquireStackName, StackMetaData.StackState.LOADING);
                stackMetaData.setState(StackMetaData.StackState.LOADING);
            }
        } catch (final Throwable t) {
            LOG.info("failed to retrieve stack metadata for {}, will attempt to create new stack", acquireStackName);
            renderDataClient.saveStackVersion(acquireStackName,
                    new StackVersion(new Date(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
            stackMetaData = renderDataClient.getStackMetaData(acquireStackName);
        }

        if (!stackMetaData.isLoading()) {
            throw new IllegalStateException("stack state is " + stackMetaData.getState() +
                    " but must be LOADING, stackMetaData=" + stackMetaData);
        }
        LOG.info("ensureAcquireStackExists: exit, stackMetaData is {}", stackMetaData);
    }

    private static final Logger LOG = LoggerFactory.getLogger(MontageAcquisitionClient.class);
}
