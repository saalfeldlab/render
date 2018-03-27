package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.validator.TileSpecValidator;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.TileSpecValidatorParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for importing JSON tile and transform specifications into the render database.
 *
 * @author Eric Trautman
 */
public class ImportJsonClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public TileSpecValidatorParameters tileSpecValidator = new TileSpecValidatorParameters();

        @Parameter(
                names = "--stack",
                description = "Name of stack for imported data",
                required = true)
        public String stack;

        @Parameter(
                names = "--transformFile",
                description = "file containing shared JSON transform specs (.json, .gz, or .zip)",
                required = false)
        public String transformFile;

        @Parameter(
                description = "list of tile spec files (.json, .gz, or .zip)",
                required = true)
        public List<String> tileFiles;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final ImportJsonClient client = new ImportJsonClient(parameters);

                for (final String tileFile : parameters.tileFiles) {
                    client.importStackData(tileFile);
                }
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final TileSpecValidator tileSpecValidator;

    private final RenderDataClient renderDataClient;
    private final List<TransformSpec> transformSpecs;

    public ImportJsonClient(final Parameters parameters)
            throws IOException {
        this.parameters = parameters;
        this.tileSpecValidator = parameters.tileSpecValidator.getValidatorInstance();

        this.renderDataClient = parameters.renderWeb.getDataClient();

        this.renderDataClient.ensureStackIsInLoadingState(parameters.stack, null);

        this.transformSpecs = loadTransformData(parameters.transformFile);
    }

    public void importStackData(final String tileFile) throws Exception {

        LOG.info("importStackData: entry, tileFile={}", tileFile);

        final List<TileSpec> tileSpecs = loadTileData(tileFile);

        if (tileSpecs.size() > 0) {

            final ProcessTimer timer = new ProcessTimer();
            int tileSpecCount = 0;

            final ResolvedTileSpecCollection resolvedTiles =
                    new ResolvedTileSpecCollection(transformSpecs,
                                                   tileSpecs);

            for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {

                tileSpecCount++;

                tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

                if (timer.hasIntervalPassed()) {
                    LOG.info("importStackData: derived bounding box for {} out of {} tiles",
                             tileSpecCount, tileSpecs.size());
                }

                // TODO: generate mipmaps?

            }

            if ((tileSpecValidator != null) && (tileSpecCount > 0)) {

                // filter out invalid specs based upon bounding box
                resolvedTiles.setTileSpecValidator(tileSpecValidator);
                resolvedTiles.removeInvalidTileSpecs();

            }

            LOG.info("importStackData: derived bounding box for {} tiles, elapsedSeconds={}",
                      tileSpecCount, timer.getElapsedSeconds());

            renderDataClient.saveResolvedTiles(resolvedTiles, parameters.stack, null);
        }

        LOG.info("importStackData: exit, saved tiles and transforms from {}", tileFile);
    }

    public static List<TransformSpec> loadTransformData(final String transformFile)
            throws IOException {

        final List<TransformSpec> list;

        if (transformFile == null) {

            list = new ArrayList<>();

        } else {

            final Path path = Paths.get(transformFile).toAbsolutePath();

            LOG.info("loadTransformData: entry, path={}", path);

            try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
                list = TransformSpec.fromJsonArray(reader);
            }
        }

        LOG.info("loadTransformData: exit, loaded {} transform specs", list.size());

        return list;
    }

    private List<TileSpec> loadTileData(final String tileFile)
            throws IOException, IllegalArgumentException {

        final List<TileSpec> list;

        final Path path = FileSystems.getDefault().getPath(tileFile).toAbsolutePath();

        LOG.info("loadTileData: entry, path={}", path);

        try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(path.toString())) {
            list = TileSpec.fromJsonArray(reader);
        }

        LOG.info("loadTileData: exit, loaded {} tile specs", list.size());

        return list;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportJsonClient.class);
}
