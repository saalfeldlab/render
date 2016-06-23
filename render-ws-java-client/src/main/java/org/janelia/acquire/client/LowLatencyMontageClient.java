package org.janelia.acquire.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client that pulls tile data from an acquisition system, stores it in the render database, and
 * (optionally) launches another process to montage the data.
 *
 * @author Eric Trautman
 */
public class LowLatencyMontageClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, --project, --validatorClass, and --validatorData parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Name of stack for imported data", required = true)
        private String stack;

        @Parameter(names = "--finalStackState", description = "State render stack should have after import (default is COMPLETE)", required = false)
        private StackMetaData.StackState finalStackState;

        @Parameter(names = "--transformFile", description = "File containing shared JSON transform specs (.json, .gz, or .zip)", required = false)
        private String transformFile;

        @Parameter(names = "--baseAcquisitionUrl", description = "Base URL for acquisiiton data (e.g. http://host[:port]/???/v1)", required = true)
        private String baseAcquisitionUrl;

        @Parameter(names = "--acquisitionId", description = "Acquisition identifier for limiting tiles to a known acquisition", required = false)
        private String acquisitionId;

        @Parameter(names = "--acquisitionTileState", description = "Only process acquisition tiles that are in this state (default is READY)", required = false)
        private AcquisitionTileState acquisitionTileState = AcquisitionTileState.READY;

        @Parameter(names = "--waitSeconds", description = "Number of seconds to wait before checking for newly acquired tiles (default 5)", required = false)
        private int waitSeconds = 5;

        @Parameter(names = "--montageStack", description = "Montage stack name", required = true)
        private String montageStack;

        @Parameter(names = "--montageScript", description = "Full path of the montage generator script (e.g. /groups/flyTEM/.../montage_section_SL)", required = false)
        private String montageScript;

        @Parameter(names = "--montageParametersFile", description = "File containing JSON parameters for montage generation", required = false)
        private String montageParametersFile;

        @Parameter(names = "--montageWorkDirectory", description = "Parent directory for montage input data files (e.g. /nobackup/flyTEM/montage_work)", required = false)
        private String montageWorkDirectory;

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
                parameters.parse(args, LowLatencyMontageClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                parameters.validate();

                final LowLatencyMontageClient client = new LowLatencyMontageClient(parameters);
                client.ensureAcquireStackExists();
                client.importAcquisitionData();
                if (parameters.montageParametersFile != null) {
                    client.invokeMontageProcessor();
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final AcquisitionDataClient acquisitionDataClient;
    private final RenderDataClient renderDataClient;
    private final List<TransformSpec> transformSpecs;
    private final Set<Double> zValues;

    public LowLatencyMontageClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.getValidatorInstance();

        this.acquisitionDataClient = new AcquisitionDataClient(parameters.baseAcquisitionUrl);

        this.renderDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                     parameters.owner,
                                                     parameters.project);

        this.transformSpecs = ImportJsonClient.loadTransformData(parameters.transformFile);
        this.zValues = new HashSet<>();
    }

    public void ensureAcquireStackExists() throws Exception {
        LOG.info("ensureAcquireStackExists: entry");

        StackMetaData stackMetaData;
        try {
            stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        } catch (final Throwable t) {

            LOG.info("failed to retrieve stack metadata, will attempt to create new stack", t);

            renderDataClient.saveStackVersion(parameters.stack,
                                              new StackVersion(new Date(),
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null,
                                                               null));
            stackMetaData = renderDataClient.getStackMetaData(parameters.stack);
        }

        if (! stackMetaData.isLoading()) {
            throw new IllegalStateException("stack state is " + stackMetaData.getState() +
                                            " but must be LOADING, stackMetaData=" + stackMetaData);
        }

        LOG.info("ensureAcquireStackExists: exit, stackMetaData is {}", stackMetaData);
    }

    public void importAcquisitionData() throws Exception {

        LOG.info("importAcquisitionData: entry");

        final List<TransformSpec> transformSpecsCopy = new ArrayList<>(transformSpecs.size());
        transformSpecsCopy.addAll(transformSpecs);
        final ResolvedTileSpecCollection resolvedTiles =
                new ResolvedTileSpecCollection(transformSpecsCopy,
                                               new ArrayList<TileSpec>(8192));
        resolvedTiles.setTileSpecValidator(tileSpecValidator);

        final AcquisitionTileIdList completedTileIds = new AcquisitionTileIdList(AcquisitionTileState.COMPLETE,
                                                                                 new ArrayList<String>(8192));
        final AcquisitionTileIdList failedTileIds = new AcquisitionTileIdList(AcquisitionTileState.FAILED,
                                                                              new ArrayList<String>());

        final ProcessTimer timer = new ProcessTimer();
        AcquisitionTile acquisitionTile;
        int tileFoundCount = 0;
        boolean waitForMoreTiles = true;

        while (waitForMoreTiles) {

            acquisitionTile = acquisitionDataClient.getNextTile(parameters.acquisitionTileState,
                                                                AcquisitionTileState.IN_PROGRESS,
                                                                parameters.acquisitionId);
            try {

                switch (acquisitionTile.getResultType()) {

                    case NO_TILE_READY:
                        LOG.info("importAcquisitionData: no tile ready, waiting {}s", parameters.waitSeconds);
                        Thread.sleep(parameters.waitSeconds * 1000);
                        break;

                    case TILE_FOUND:
                        tileFoundCount++;
                        addTileSpec(acquisitionTile, resolvedTiles);
                        if (timer.hasIntervalPassed()) {
                            LOG.info("importAcquisitionData: processed {} tiles", tileFoundCount);
                        }
                        break;

                    case SERVED_ALL_ACQ:
                        waitForMoreTiles = false;
                        break;

                    case SERVED_ALL_SECTION:
                    case NO_TILE_READY_IN_SECTION:
                        throw new IllegalStateException("received invalid resultType, acquisitionTile=" +
                                                        acquisitionTile);
                }

            } catch (final Throwable t) {
                LOG.error("failed to process acquisition tile: " + acquisitionTile, t);
                final String failedTileSpecId = acquisitionTile.getTileSpecId();
                if (failedTileSpecId != null) {
                    failedTileIds.addTileSpecId(failedTileSpecId);
                }
            }
        }

        LOG.info("importAcquisitionData: processed {} tiles, elapsedSeconds={}",
                 tileFoundCount, timer.getElapsedSeconds());

        if (resolvedTiles.hasTileSpecs()) {

            try {

                renderDataClient.saveResolvedTiles(resolvedTiles, parameters.stack, null);

                for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
                    completedTileIds.addTileSpecId(tileSpec.getTileId());
                    zValues.add(tileSpec.getZ());
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
        renderDataClient.setStackState(parameters.stack, StackMetaData.StackState.COMPLETE);

        if ((parameters.finalStackState != null) &&
            (! StackMetaData.StackState.COMPLETE.equals(parameters.finalStackState))) {
            renderDataClient.setStackState(parameters.stack, parameters.finalStackState);
        }

        LOG.info("importAcquisitionData: exit");
    }

    public void invokeMontageProcessor()
            throws IllegalStateException, IOException, InterruptedException {

        if (zValues.size() == 0) {
            throw new IllegalStateException("no tiles are available for montage processing");
        }

        final MontageParameters montageParameters = MontageParameters.load(parameters.montageParametersFile);

        montageParameters.setSourceCollection(
                new AlignmentRenderCollection(parameters.baseDataUrl,
                                              parameters.owner, parameters.project, parameters.stack));

        montageParameters.setTargetCollection(
                new AlignmentRenderCollection(parameters.baseDataUrl,
                                              parameters.owner, parameters.project, parameters.montageStack));

        final File montageWorkStackDir = Paths.get(parameters.montageWorkDirectory,
                                                   parameters.project,
                                                   parameters.montageStack).toAbsolutePath().toFile();

        if (! montageWorkStackDir.exists()) {
            if (! montageWorkStackDir.mkdirs()) {
                // check for existence again in case another parallel process already created the directory
                if (! montageWorkStackDir.exists()) {
                    throw new IOException("failed to create " + montageWorkStackDir.getAbsolutePath());
                }
            }
        }

        for (final Double z : zValues) {

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
