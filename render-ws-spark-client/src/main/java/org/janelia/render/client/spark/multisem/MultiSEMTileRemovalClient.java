package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.Parameter;
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
import org.janelia.render.client.multisem.PeakScanData;
import org.janelia.render.client.parameter.MultiSEMTileRemovalParameters;
import org.janelia.render.client.parameter.StackWithRemovalParameters;
import org.janelia.render.client.parameter.TileRemovalSetup;
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

        @Parameter(
                names = "--peakScanJson",
                description = "Path or Google storage URI for peak scan JSON data " +
                              "(e.g. gs://janelia-spark-test/library/hess_wafers_60_61.peak_scan.json).  " +
                              "When specified, all scans after each slab's peak scan are removed from " +
                              "all stacks for that slab and the --scan and --scanMfov values are ignored.")
        public String peakScanJson;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final MultiProjectParameters multiProject = parameters.multiProject;
                final TileRemovalSetup tileRemovalSetup;

                if (parameters.peakScanJson == null) {

                    parameters.tileRemoval.validate();

                    // apply the same removal parameters to each stack identified by the multiProject parameters
                    final List<StackWithRemovalParameters> stackWithRemovalList =
                            multiProject.stackIdWithZ.getStackIdList(multiProject.getDataClient()).stream()
                                    .map(stackId -> new StackWithRemovalParameters(stackId, parameters.tileRemoval))
                                    .collect(Collectors.toList());

                    tileRemovalSetup = new TileRemovalSetup(null, stackWithRemovalList);

                } else {

                    tileRemovalSetup = new TileRemovalSetup(parameters.peakScanJson, null);

                }

                // NOTE: no spark context is needed here because all removal is run on the driver
                final MultiSEMTileRemovalClient client = new MultiSEMTileRemovalClient();
                client.removeTiles(multiProject.getBaseDataUrl(), tileRemovalSetup);
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

        final TileRemovalSetup tileRemovalSetup = pipelineParameters.getTileRemoval();

        AlignmentPipelineParameters.validateRequiredElementExists("tileRemoval",
                                                                  tileRemovalSetup);

        tileRemovalSetup.validate();
    }

    /** Run the client as part of an alignment pipeline. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        removeTiles(pipelineParameters.getMultiProject(null).getBaseDataUrl(),
                    pipelineParameters.getTileRemoval());
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.REMOVE_TILES;
    }

    public void removeTiles(final String baseDataUrl,
                            final TileRemovalSetup tileRemovalSetup)
            throws IOException {

        LOG.info("removeTiles: entry, tileRemovalSetup={}", tileRemovalSetup);

        if (tileRemovalSetup.hasPeakScanJson()) {
            removeScansAfterPeak(baseDataUrl, tileRemovalSetup.getPeakScanJson());
        }

        if (tileRemovalSetup.hasStackList()) {
            removeTilesForStackList(baseDataUrl, tileRemovalSetup.getStackList());
        }

        LOG.info("removeTiles: exit");
    }

    /**
     * Removes all layers for scans after the peak scan from every stack
     * associated with a slab in the specified peak scan data.
     *
     * @throws IOException
     *   if the peak scan data cannot be read or if any request fails.
     */
    private void removeScansAfterPeak(final String baseDataUrl,
                                      final String peakScanJson)
            throws IOException {

        final PeakScanData peakScanData = PeakScanData.fromJson(peakScanJson);
        final String owner = peakScanData.getOwner();
        final Set<String> peakScanProjects = peakScanData.getProjectNames();

        final org.janelia.render.client.multisem.MultiSEMTileRemovalClient javaClient =
                new org.janelia.render.client.multisem.MultiSEMTileRemovalClient();

        // fetch all of the owner's stacks with one request instead of one request per project
        final RenderDataClient ownerDataClient = new RenderDataClient(baseDataUrl,
                                                                      owner,
                                                                      peakScanProjects.iterator().next());
        final List<StackId> ownerStackIds = ownerDataClient.getOwnerStacks();

        int peakStackCount = 0;
        for (final StackId stackId : ownerStackIds) {

            final String project = stackId.getProject();

            if (peakScanProjects.contains(project)) {

                final Integer peakScanNumber = peakScanData.getPeakScanForStack(project, stackId.getStack());

                if (peakScanNumber == null) {
                    LOG.info("removeScansAfterPeak: skipping {} because there is no peak scan for its slab",
                             stackId.toDevString());
                } else {
                    final RenderDataClient dataClient = ownerDataClient.buildClient(owner, project);
                    javaClient.removeScansAfterPeak(dataClient, stackId.getStack(), peakScanNumber);
                    peakStackCount++;
                }
            }
        }

        LOG.info("removeScansAfterPeak: processed {} of the {} stack(s) for owner {}",
                 peakStackCount, ownerStackIds.size(), owner);
    }

    /**
     * Removes the scans and MFOVs identified for each stack in the specified list.
     *
     * @throws IOException
     *   if any request fails.
     */
    private void removeTilesForStackList(final String baseDataUrl,
                                         final List<StackWithRemovalParameters> stackWithRemovalList)
            throws IOException {

        LOG.info("removeTilesForStackList: entry, processing {} stack(s)", stackWithRemovalList.size());

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
                LOG.info("removeTilesForStackList: processing {}", stackId.toDevString());
                javaClient.removeTiles(dataClient, stackId.getStack(), stackWithRemoval.getTileRemoval());
            } else {
                LOG.info("removeTilesForStackList: skipping removal for {} because the stack does not exist",
                         stackId.toDevString());
            }
        }

        LOG.info("removeTilesForStackList: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(MultiSEMTileRemovalClient.class);
}
