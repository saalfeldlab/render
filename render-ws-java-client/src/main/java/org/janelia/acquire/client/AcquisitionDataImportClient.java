package org.janelia.acquire.client;

import com.beust.jcommander.Parameter;
import org.janelia.acquire.client.model.*;
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
import org.janelia.render.client.RenderDataClientParametersWithValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Java client that pulls tile data from an acquisition system and stores it in the render database.
 *
 * @author Cristian Goina (based on Eric Trautman's LowLatencyMontageClient)
 */
public class AcquisitionDataImportClient {

    @SuppressWarnings("ALL")
    static class Parameters extends RenderDataClientParametersWithValidator {

        // NOTE: --baseDataUrl, --owner, --project, --validatorClass, and --validatorData parameters defined in RenderDataClientParameters
        // NOTE: --validatorClass and --validatorData parameters defined in RenderDataClientParametersWithValidator

        @Parameter(names = "--finalStackState", description = "State render stack should have after import (default is COMPLETE)", required = false)
        StackMetaData.StackState finalStackState;

        @Parameter(names = "--overrideOwnerFromParameter", arity = 0, description = "If the parameter is set it will always use the owner specified in the command line parameter", required = false)
        Boolean overrideOwnerFromParameter;

        @Parameter(names = "--transformFile", description = "File containing shared JSON transform specs (.json, .gz, or .zip)", required = false)
        String transformFile;

        @Parameter(names = "--baseAcquisitionUrl", description = "Base URL for acquisiiton data (e.g. http://host[:port]/???/v1)", required = true)
        String baseAcquisitionUrl;

        @Parameter(names = "--acquisitionFromFilter", description = "Acquisition filter by acquisition date", required = false)
        String acquisitionFromFilter;

        @Parameter(names = "--acquisitionId", description = "Acquisition identifier for limiting tiles to a known acquisition", required = false)
        Long acquisitionId;

        @Parameter(names = "--ownerFilter", description = "Acquisition filter by owner", required = false)
        String ownerFilter;

        @Parameter(names = "--projectFilter", description = "Acquisition filter by project", required = false)
        String projectFilter;

        @Parameter(names = "--stackFilter", description = "Acquisition filter by stack", required = false)
        String stackFilter;

        @Parameter(names = "--mosaicType", description = "Acquisition filter by mosaic type (e.g. high_mag)", required = false)
        String mosaicType;

        @Parameter(names = "--acquisitionTileState", description = "Only process acquisition tiles that are in this state (default is READY)", required = false)
        AcquisitionTileState acquisitionTileState = AcquisitionTileState.READY;

        @Parameter(names = "--acquisitionTileCount", description = "Maximum number of acquisition tiles to retrieve in each request", required = false)
        Integer acquisitionTileCount = 1;

        @Parameter(names = "--waitSeconds", description = "Number of seconds to wait before checking for newly acquired tiles (default 5)", required = false)
        int waitSeconds = 5;

        public void validate()
                throws IllegalArgumentException {

            if (AcquisitionTileState.IN_PROGRESS.equals(acquisitionTileState)) {
                throw new IllegalArgumentException("acquisitionTileState cannot be " + AcquisitionTileState.IN_PROGRESS);
            }
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args, AcquisitionDataImportClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.validate();

                final AcquisitionDataImportClient client = new AcquisitionDataImportClient(parameters);
                while (true) {
                    try {
                        client.processAcquisitions();
                        if (client.hasFailedAcquisitions()) {
                            LOG.error("There are failed acquisitions: {}", client.getFailedAcquisitionIds());
                        }
                        if (parameters.acquisitionId != null && parameters.acquisitionId != 0) {
                            // if this import was for a specific acquisition then stop
                            break;
                        }
                    } catch (final Exception e) {
                        LOG.error("Process acquisitions failure", e);
                    }
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

    public AcquisitionDataImportClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.getValidatorInstance();

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

        LOG.info("Retrieve unprocessed acquisition using: {}", parameters);
        final List<Acquisition> unprocessedAcquisitions =
                acquisitionDataClient.getAcquisitionsUsingFilter(parameters);

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
            String ownerName = acquisition.getProjectOwner();
            if (parameters.overrideOwnerFromParameter) {
                ownerName = parameters.owner;
            }
            if (ownerName == null || ownerName.trim().length() == 0) {
                throw new IllegalArgumentException("Owner for acquisition " + acqId + " is null");
            }
            final String projectName = getRequiredValue("ProjectName for acquisition " + acqId,
                                                        acquisition.getProjectName().replace("-", "_"));

            String baseStackName = acquisition.getStackName();
            if (baseStackName == null) {
                baseStackName = acquisition.getAcqUID().toString();
            }
            baseStackName = baseStackName.trim();
            if (baseStackName.length() == 0) {
                baseStackName = acquisition.getAcqUID().toString();
            }

            final String acquireStackName = baseStackName + "_acquire";

            final RenderDataClient renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                           ownerName,
                                                                           projectName);

            ensureAcquireStackExists(renderDataClient,
                                     acquireStackName,
                                     acquisition);

            importAcquisitionData(renderDataClient, acqId, acquireStackName);
        } catch (final Throwable t) {
            LOG.error("failed to process acquisition '" + acqId + "'", t);
            failedAcquisitionIds.add(acqId);
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

    private static final Logger LOG = LoggerFactory.getLogger(AcquisitionDataImportClient.class);
}
