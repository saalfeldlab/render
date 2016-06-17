package org.janelia.acquire.client;

import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.ImportJsonClient;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for ...
 *
 * @author Eric Trautman
 */
public class LowLatencyMontageClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, --project, --validatorClass, and --validatorData parameters defined in RenderDataClientParameters

        @Parameter(names = "--stack", description = "Name of stack for imported data", required = true)
        private String stack;

        @Parameter(names = "--transformFile", description = "File containing shared JSON transform specs (.json, .gz, or .zip)", required = false)
        private String transformFile;

        @Parameter(names = "--baseAcquisitionUrl", description = "Base URL for acquisiiton data (e.g. http://host[:port]/???/v1)", required = true)
        private String baseAcquisitionUrl;

        @Parameter(names = "--acquisitionId", description = "Acquisition identifier for limiting tiles to a known acquisition", required = false)
        private String acquisitionId;

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

        public void validateMontageParameters()
                throws IllegalArgumentException {
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

                parameters.validateMontageParameters();

                final LowLatencyMontageClient client = new LowLatencyMontageClient(parameters);
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

    public void importAcquisitionData() throws Exception {

        LOG.info("importAcquisitionData: entry");

        final List<TransformSpec> transformSpecsCopy = new ArrayList<>(transformSpecs.size());
        transformSpecsCopy.addAll(transformSpecs);
        final ResolvedTileSpecCollection resolvedTiles =
                new ResolvedTileSpecCollection(transformSpecsCopy,
                                               new ArrayList<TileSpec>(8192));
        resolvedTiles.setTileSpecValidator(tileSpecValidator);

        final ProcessTimer timer = new ProcessTimer();
        AcquisitionTile acquisitionTile;
        int tileFoundCount = 0;
        boolean waitForMoreTiles = true;

        while (waitForMoreTiles) {

            acquisitionTile = acquisitionDataClient.getNextTile(AcquisitionTileState.READY,
                                                                AcquisitionTileState.IN_PROGRESS,
                                                                parameters.acquisitionId);

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
        }

        LOG.info("importAcquisitionData: processed {} tiles, elapsedSeconds={}",
                 tileFoundCount, timer.getElapsedSeconds());

        if (resolvedTiles.hasTileSpecs()) {
            renderDataClient.saveResolvedTiles(resolvedTiles, parameters.stack, null);
        }

        final List<String> tileIds = new ArrayList<>(resolvedTiles.getTileCount());
        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            tileIds.add(tileSpec.getTileId());
        }
        final AcquisitionTileIdList acquisitionTileIdList =
                new AcquisitionTileIdList(AcquisitionTileState.COMPLETE,
                                          tileIds);
        acquisitionDataClient.updateTileStates(acquisitionTileIdList);

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
                final ProcessBuilder processBuilder = new ProcessBuilder(parameters.montageScript,
                                                                         montageInputFile.getAbsolutePath());

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
            throw new IllegalStateException("acquisition tile is missing render tile spec\ntileJSON=" +
                                            fromAcquisitionTile.toJson());
        }

        // add to collection to resolve any transform references
        toCollection.addTileSpecToCollection(tileSpec);

        // re-calculate bounding box attributes
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        if ((tileSpecValidator == null) || (! toCollection.removeTileSpecIfInvalid(tileSpec))) {
            zValues.add(tileSpec.getZ());
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LowLatencyMontageClient.class);
}
