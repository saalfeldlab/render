package org.janelia.acquire.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.janelia.acquire.client.model.Acquisition;
import org.janelia.acquire.client.model.AcquisitionTile;
import org.janelia.acquire.client.model.AcquisitionTileIdList;
import org.janelia.acquire.client.model.AcquisitionTileList;
import org.janelia.acquire.client.model.AcquisitionTileState;
import org.janelia.acquire.client.model.Calibration;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.ImportJsonClient;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that pulls tile data from an acquisition system, stores it in the render database, and
 * (optionally) launches another process to montage the data.
 *
 * @author Eric Trautman
 */
public class LowLatencyMontageClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @Parameter(
                names = "--finalStackState",
                description = "State render stack should have after import (default is COMPLETE)",
                required = false)
        public StackMetaData.StackState finalStackState;

        @Parameter(
                names = "--transformFile",
                description = "File containing shared JSON transform specs (.json, .gz, or .zip)",
                required = false)
        public String transformFile;

        @Parameter(
                names = "--baseAcquisitionUrl",
                description = "Base URL for acquisiiton data (e.g. http://host[:port]/???/v1)",
                required = true)
        public String baseAcquisitionUrl;

        @Parameter(
                names = "--acquisitionId",
                description = "Acquisition identifier for limiting tiles to a known acquisition",
                required = false)
        public Long acquisitionId;

        @Parameter(
                names = "--acquisitionTileState",
                description = "Only process acquisition tiles that are in this state (default is READY)",
                required = false)
        public AcquisitionTileState acquisitionTileState = AcquisitionTileState.READY;

        @Parameter(
                names = "--acquisitionTileCount",
                description = "Maximum number of acquisition tiles to retrieve in each request",
                required = false)
        public Integer acquisitionTileCount = 1;

        @Parameter(
                names = "--waitSeconds",
                description = "Number of seconds to wait before checking for newly acquired tiles (default 5)",
                required = false)
        public int waitSeconds = 5;

        @Parameter(
                names = "--montageScript",
                description = "Full path of the montage generator script (e.g. /groups/flyTEM/.../montage_section_SL)",
                required = false)
        public String montageScript;

        @Parameter(
                names = "--montageParametersFile",
                description = "File containing JSON parameters for montage generation",
                required = false)
        public String montageParametersFile;

        @Parameter(
                names = "--montageWorkDirectory",
                description = "Parent directory for montage input data files (e.g. /nobackup/flyTEM/montage_work)",
                required = false)
        public String montageWorkDirectory;

        public void validate()
                throws IllegalArgumentException {

            if (AcquisitionTileState.IN_PROGRESS.equals(acquisitionTileState)) {
                throw new IllegalArgumentException("acquisitionTileState cannot be " + AcquisitionTileState.IN_PROGRESS);
            }

            if ((montageParametersFile == null) && (montageWorkDirectory != null)) {
                throw new IllegalArgumentException("montageParametersFile must be specified when montageWorkDirectory is specified");
            } else if ((montageParametersFile != null) && (montageWorkDirectory == null)) {
                throw new IllegalArgumentException("montageWorkDirectory must be specified when montageParametersFile is specified");
            }
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.validate();

                final LowLatencyMontageClient client = new LowLatencyMontageClient(parameters);

                if (parameters.acquisitionId == null) {
                    //noinspection InfiniteLoopStatement
                    while (true) {
                        client.processAcquisitions();
                    }
                } else {
                    client.processAcquisition(parameters.acquisitionId);
                }

                if (client.hasFailedAcquisitions()) {
                    throw new IllegalStateException(
                            "run completed but the following acquisitions could not be processed: " +
                            client.getFailedAcquisitionIds());
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final AcquisitionDataClient acquisitionDataClient;
    private List<TransformSpec> transformSpecs;
    private final Set<Long> failedAcquisitionIds;

    public LowLatencyMontageClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        this.acquisitionDataClient = new AcquisitionDataClient(parameters.baseAcquisitionUrl);

        if (parameters.transformFile != null) {
            this.transformSpecs = ImportJsonClient.loadTransformData(parameters.transformFile);
        }

        this.failedAcquisitionIds = new HashSet<>();
    }

    public boolean hasFailedAcquisitions() {
        return failedAcquisitionIds.size() > 0;
    }

    public Set<Long> getFailedAcquisitionIds() {
        return failedAcquisitionIds;
    }

    /**
     * Retrieves current list of unprocessed acquisitions and processes them sequentially.
     * The ids of all acquisitions that fail processing are tracked so that we do not
     * attempt to reprocess them.
     */
    public void processAcquisitions()
            throws Exception {

        final List<Acquisition> unprocessedAcquisitions =
                acquisitionDataClient.getAcquisitions(parameters.acquisitionTileState, null);

        // filter out any acquisitions that have already failed to process
        for (final Iterator<Acquisition> i = unprocessedAcquisitions.iterator(); i.hasNext();) {
            final Acquisition acq = i.next();
            if (failedAcquisitionIds.contains(acq.getAcqUID())) {
                i.remove();
            }
        }

        if (unprocessedAcquisitions.size() == 0) {

            LOG.info("processAcquisitions: no acquisitions with tiles in state {}, waiting {}s",
                     parameters.acquisitionTileState, parameters.waitSeconds);
            Thread.sleep(parameters.waitSeconds * 1000);

        } else {

            // make sure we have the latest lens correction data
            if (parameters.transformFile == null) {
                final List<Calibration> calibrations = this.acquisitionDataClient.getCalibrations();
                this.transformSpecs = new ArrayList<>(calibrations.size());
                for (final Calibration calibration : calibrations) {
                    this.transformSpecs.add(calibration.getContent());
                }
            }

            for (final Acquisition acquisition : unprocessedAcquisitions) {
                // Theoretically, each acquisition could be processed concurrently in its own thread.
                // We've decided to serialize processing for now to maximize the resources
                // available to the montage tool for point match derivation.
                processAcquisition(acquisition);
            }

        }

    }

    /**
     * Process the specified acquisition.
     * If processing fails, the problem is logged and the acquisition id is tracked but no exception is thrown.
     */
    public void processAcquisition(final Acquisition acquisition) {

        final Long acqId = acquisition.getAcqUID();

        try {
            final String ownerName = getRequiredValue("ProjectOwner for acquisition " + acqId,
                                                      acquisition.getProjectOwner()).replaceAll("-","_");
            final String projectName = getRequiredValue("ProjectName for acquisition " + acqId,
                                                        acquisition.getProjectName()).replaceAll("-","_");

            String baseStackName = acquisition.getStackName();
            if (baseStackName == null) {
                baseStackName = acquisition.getAcqUID().toString();
            }
            baseStackName = baseStackName.trim().replaceAll("-","_");
            if (baseStackName.length() == 0) {
                baseStackName = acquisition.getAcqUID().toString();
            }

            final String acquireStackName = baseStackName + "_acquire";
            final String montageStackName = baseStackName + "_montage";

            final RenderDataClient renderDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                           ownerName,
                                                                           projectName);

            ensureAcquireStackExists(renderDataClient,
                                     acquireStackName,
                                     acquisition);

            final Set<Double> acquisitionZValues = importAcquisitionData(renderDataClient, acqId, acquireStackName);
            if (parameters.montageParametersFile != null) {
                invokeMontageProcessor(ownerName, projectName, acquireStackName, acquisitionZValues, montageStackName);
            }

        } catch (final Throwable t) {
            LOG.error("failed to process acquisition '" + acqId + "'", t);
            failedAcquisitionIds.add(acqId);
        }

    }

    /**
     * Retrieve data for the specified acquisition and then process it.
     */
    public void processAcquisition(final Long acquisitionId)
            throws IOException {

        final List<Acquisition> unprocessedAcquisitions =
                acquisitionDataClient.getAcquisitions(parameters.acquisitionTileState, acquisitionId);

        for (final Acquisition acquisition : unprocessedAcquisitions) {
            processAcquisition(acquisition);
        }

    }

    public void ensureAcquireStackExists(final RenderDataClient renderDataClient,
                                         final String acquireStackName,
                                         final Acquisition acquisition) throws Exception {
        LOG.info("ensureAcquireStackExists: entry");

        StackMetaData stackMetaData;
        try {
            stackMetaData = renderDataClient.getStackMetaData(acquireStackName);
        } catch (final Throwable t) {

            LOG.info("failed to retrieve stack metadata, will attempt to create new stack");

            renderDataClient.saveStackVersion(acquireStackName,
                                              new StackVersion(new Date(),
                                                               null,
                                                               null,
                                                               null,
                                                               acquisition.getStackResolutionX(),
                                                               acquisition.getStackResolutionY(),
                                                               acquisition.getStackResolutionZ(),
                                                               null,
                                                               null));

            stackMetaData = renderDataClient.getStackMetaData(acquireStackName);
        }

        if (! stackMetaData.isLoading()) {
            throw new IllegalStateException("stack state is " + stackMetaData.getState() +
                                            " but must be LOADING, stackMetaData=" + stackMetaData);
        }

        LOG.info("ensureAcquireStackExists: exit, stackMetaData is {}", stackMetaData);
    }

    /**
     * Pull tile data for the specified acquisition until the Image Catcher indicates that
     * all tiles have been captured.
     */
    public Set<Double> importAcquisitionData(final RenderDataClient renderDataClient,
                                             final Long acquisitionId,
                                             final String acquireStackName) throws Exception {

        LOG.info("importAcquisitionData: entry, acquisitionId={}, acquireStackName={}",
                 acquisitionId, acquireStackName);

        final Set<Double> acquisitionZValues = new TreeSet<>();

        final List<TransformSpec> transformSpecsCopy = new ArrayList<>(transformSpecs.size());
        transformSpecsCopy.addAll(transformSpecs);
        final ResolvedTileSpecCollection resolvedTiles =
                new ResolvedTileSpecCollection(transformSpecsCopy,
                                               new ArrayList<>(8192));
        resolvedTiles.setTileSpecValidator(tileSpecValidator);

        final AcquisitionTileIdList completedTileIds = new AcquisitionTileIdList(AcquisitionTileState.COMPLETE,
                                                                                 new ArrayList<>(8192));
        final AcquisitionTileIdList failedTileIds = new AcquisitionTileIdList(AcquisitionTileState.FAILED,
                                                                              new ArrayList<>());

        final ProcessTimer timer = new ProcessTimer();
        AcquisitionTileList acquisitionTileList;
        int tileFoundCount = 0;
        boolean waitForMoreTiles = true;

        while (waitForMoreTiles) {

            acquisitionTileList = acquisitionDataClient.getNextTiles(parameters.acquisitionTileState,
                                                                     AcquisitionTileState.IN_PROGRESS,
                                                                     acquisitionId,
                                                                     parameters.acquisitionTileCount);
            try {

                switch (acquisitionTileList.getResultType()) {

                    case NO_TILE_READY:
                        LOG.info("importAcquisitionData: no tile ready, waiting {}s", parameters.waitSeconds);
                        Thread.sleep(parameters.waitSeconds * 1000);
                        break;

                    case TILE_FOUND:
                        for (final AcquisitionTile acquisitionTile : acquisitionTileList.getResults()) {

                            tileFoundCount++;

                            try {
                                addTileSpec(acquisitionTile, resolvedTiles);
                            } catch (final Throwable t) {
                                LOG.error("failed to process acquisition tile: " + acquisitionTile, t);
                                final String failedTileSpecId = acquisitionTile.getTileSpecId();
                                if (failedTileSpecId != null) {
                                    failedTileIds.addTileSpecId(failedTileSpecId);
                                }
                            }

                            if (timer.hasIntervalPassed()) {
                                LOG.info("importAcquisitionData: processed {} tiles", tileFoundCount);
                            }

                        }
                        break;

                    case SERVED_ALL_ACQ:
                        waitForMoreTiles = false;
                        break;

                    case SERVED_ALL_SECTION:
                    case NO_TILE_READY_IN_SECTION:
                        throw new IllegalStateException("received unexpected resultType " +
                                                        acquisitionTileList.getResultType() +
                                                        " for acquisition id " + acquisitionId);
                }

            } catch (final Throwable t) {
                LOG.error("failed to process acquisition id " + acquisitionId, t);
            }
        }

        LOG.info("importAcquisitionData: processed {} tiles, elapsedSeconds={}",
                 tileFoundCount, timer.getElapsedSeconds());

        if (resolvedTiles.hasTileSpecs()) {

            try {

                renderDataClient.saveResolvedTiles(resolvedTiles, acquireStackName, null);

                for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                    completedTileIds.addTileSpecId(tileSpec.getTileId());
                    acquisitionZValues.add(tileSpec.getZ());
                }

            } catch (final Throwable t) {

                LOG.error("failed to save tiles to render database", t);

                for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                    failedTileIds.addTileSpecId(tileSpec.getTileId());
                }
            }

        }

        if (completedTileIds.size() > 0) {
            acquisitionDataClient.updateTileStates(completedTileIds);
        }

        if (failedTileIds.size() > 0) {
            acquisitionDataClient.updateTileStates(failedTileIds);
        }

        // "complete" acquire stack so that indexes and meta-data are refreshed
        renderDataClient.setStackState(acquireStackName, StackMetaData.StackState.COMPLETE);

        if ((parameters.finalStackState != null) &&
            (! StackMetaData.StackState.COMPLETE.equals(parameters.finalStackState))) {
            renderDataClient.setStackState(acquireStackName, parameters.finalStackState);
        }

        LOG.info("importAcquisitionData: exit, acquired data for {} sections", acquisitionZValues.size());

        return acquisitionZValues;
    }

    /**
     * Call montage tool to derive point matches and generate montage stack data for an acquisition.
     */
    public void invokeMontageProcessor(final String owner,
                                       final String project,
                                       final String acquireStack,
                                       final Set<Double> acquisitionZValues,
                                       final String montageStack)
            throws Exception {

        if (acquisitionZValues.size() == 0) {
            throw new IllegalStateException("no tiles are available for montage processing");
        }

        final MontageParameters montageParameters = MontageParameters.load(parameters.montageParametersFile);

        montageParameters.setSourceCollection(
                new AlignmentRenderCollection(parameters.renderWeb.baseDataUrl,
                                              owner, project, acquireStack));

        montageParameters.setTargetCollection(
                new AlignmentRenderCollection(parameters.renderWeb.baseDataUrl,
                                              owner, project, montageStack));

        final File montageWorkStackDir = Paths.get(parameters.montageWorkDirectory,
                                                   project,
                                                   montageStack).toAbsolutePath().toFile();

        if (! montageWorkStackDir.exists()) {
            if (! montageWorkStackDir.mkdirs()) {
                // check for existence again in case another parallel process already created the directory
                if (! montageWorkStackDir.exists()) {
                    throw new IOException("failed to create " + montageWorkStackDir.getAbsolutePath());
                }
            }
        }

        for (final Double z : acquisitionZValues) {

            montageParameters.setSectionNumber(z);

            final String fileName = String.format("montage_input_%08.1f.json", z);
            final File montageInputFile = new File(montageWorkStackDir, fileName);
            montageParameters.save(montageInputFile.getAbsolutePath());

            if (parameters.montageScript == null) {
                LOG.info("invokeMontageProcessor: no montageScript to execute");
            } else {
                final ProcessBuilder processBuilder =
                        new ProcessBuilder(parameters.montageScript,
                                           montageInputFile.getAbsolutePath()).inheritIO();

                LOG.info("invokeMontageProcessor: running {}", processBuilder.command());

                final Process process = processBuilder.start();
                final int montageReturnCode = process.waitFor();

                if (montageReturnCode != 0) {
                    LOG.error("invokeMontageProcessor: code {} returned", montageReturnCode);
                } else {
                    LOG.info("invokeMontageProcessor: code {} returned", montageReturnCode);
                }
            }

        }

    }

    private String getRequiredValue(final String context,
                                    final String originalValue) {
        final String value;
        if (originalValue == null) {
            throw new IllegalArgumentException(context + " is null");
        } else {
            value = originalValue.trim();
            if (value.length() == 0) {
                throw new IllegalArgumentException(context + " is empty");
            }
        }
        return value;
    }

    private void addTileSpec(final AcquisitionTile fromAcquisitionTile,
                             final ResolvedTileSpecCollection toCollection) {

        final TileSpec tileSpec = fromAcquisitionTile.getTileSpec();

        if (tileSpec == null) {
            throw new IllegalStateException("acquisition tile is missing render tile spec\ntile=" +
                                            fromAcquisitionTile.toJson());
        } else if (toCollection.hasTileSpec(tileSpec.getTileId())) {
            final TileSpec existingTileSpec = toCollection.getTileSpec(tileSpec.getTileId());
            throw new IllegalStateException("duplicate tileSpecId \nexisting tileSpec=" +
                                            existingTileSpec.toJson() + "\nduplicate acquisitionTile=" +
                                            fromAcquisitionTile.toJson());
        }

        // add to collection to resolve any transform references
        toCollection.addTileSpecToCollection(tileSpec);

        // re-calculate bounding box attributes
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        toCollection.removeTileSpecIfInvalid(tileSpec);
    }

    private static final Logger LOG = LoggerFactory.getLogger(LowLatencyMontageClient.class);
}
