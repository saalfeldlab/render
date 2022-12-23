package org.janelia.render.client.tile;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering one or more tiles with a new list of transforms.
 *
 * @author Eric Trautman
 */
public class RenderTileWithTransformsClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @Parameter(
                names = "--stack",
                description = "Stack name",
                required = true)
        public String stack;

        @Parameter(
                names = "--rootDirectory",
                description = "Root directory for rendered tiles (e.g. /nrs/flyem/render/tiles)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--format",
                description = "Format for rendered tiles"
        )
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--tileId",
                description = "Explicit IDs for tiles to render",
                variableArity = true,
                required = true
        )
        public List<String> tileIds;

        @Parameter(
                names = "--transformFile",
                description = "File containing list of transform changes (.json, .gz, or .zip).  Omit to exclude all transforms.")
        public String transformFile;
    }

    /**
     * @param  args  see {@link Parameters} for command line argument details.
     */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);
                client.renderTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;
    private final List<TransformSpec> transformSpecList;

    private final File tileDirectory;
    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;

    public RenderTileWithTransformsClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;

        final Path tileDirectoryPath = Paths.get(parameters.rootDirectory);
        this.tileDirectory = tileDirectoryPath.toAbsolutePath().toFile();

        FileUtil.ensureWritableDirectory(this.tileDirectory);

        if (parameters.transformFile != null) {
            try (final Reader reader = FileUtil.DEFAULT_INSTANCE.getExtensionBasedReader(parameters.transformFile)) {
                this.transformSpecList = TransformSpec.fromJsonArray(reader);
            }
        } else {
            this.transformSpecList = new ArrayList<>();
        }

        // set cache size to 50MB so that masks get cached but most of RAM is left for target images
        final int maxCachedPixels = 50 * 1000000;
        this.imageProcessorCache = new ImageProcessorCache(maxCachedPixels,
                                                           false,
                                                           false);

        this.renderDataClient = parameters.renderWeb.getDataClient();
    }

    public void renderTiles()
            throws IOException {
        for (final String tileId : parameters.tileIds) {
            renderTile(tileId);
        }
    }

    public void renderTile(final String tileId)
            throws IOException {

        final TileSpec tileSpec = renderDataClient.getTile(parameters.stack, tileId);
        int removalCount = 0;
        while (tileSpec.hasTransforms()) {
            tileSpec.removeLastTransformSpec();
            removalCount++;
        }

        tileSpec.addTransformSpecs(transformSpecList);

        LOG.info("renderTile: removed {} existing transforms and added {} new transforms to tile {}",
                 removalCount, transformSpecList.size(), tileId);

        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

        final double tileRenderX = tileSpec.getMinX();
        final double tileRenderY = tileSpec.getMinY();
        final int tileRenderWidth = (int) (tileSpec.getMaxX() - tileSpec.getMinX());
        final int tileRenderHeight = (int) (tileSpec.getMaxY() - tileSpec.getMinY());

        final RenderParameters renderParameters =
                new RenderParameters(null,
                                     tileRenderX,
                                     tileRenderY,
                                     tileRenderWidth,
                                     tileRenderHeight,
                                     1.0);
        renderParameters.addTileSpec(tileSpec);
        renderParameters.initializeDerivedValues();

        final File tileFile = new File(tileDirectory,
                                       tileSpec.getTileId() + "." + parameters.format.toLowerCase());

        Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache, tileFile);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTileWithTransformsClient.class);
}
