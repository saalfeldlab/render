package org.janelia.render.client.tile;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.ByteRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.Utils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasIdWithRenderContext;
import org.janelia.alignment.match.parameters.FeatureRenderClipParameters;
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
                names = "--scale",
                description = "Scale for each rendered tile"
        )
        public Double scale = 1.0;

        @ParametersDelegate
        FeatureRenderClipParameters featureRenderClip = new FeatureRenderClipParameters();

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

        @Parameter(
                names = "--renderWithoutMask",
                description = "Render tiles without a mask",
                arity = 0)
        public boolean renderWithoutMask;
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

    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;

    public RenderTileWithTransformsClient(final Parameters parameters)
            throws IOException {

        this.parameters = parameters;

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

        final Path tileDirectoryPath = Paths.get(parameters.rootDirectory);
        final File tileDirectory = tileDirectoryPath.toAbsolutePath().toFile();

        FileUtil.ensureWritableDirectory(tileDirectory);

        for (final String tileId : parameters.tileIds) {
            final File saveTileFile = new File(tileDirectory,
                                               tileId + "." + parameters.format.toLowerCase());
            final TileSpec tileSpec = fetchTileSpec(tileId);
            renderTile(tileSpec, transformSpecList, parameters.scale, null, saveTileFile);
        }
    }

    public TileSpec fetchTileSpec(final String tileId)
            throws IOException {
        return renderDataClient.getTile(parameters.stack, tileId);
    }

    public TransformMeshMappingWithMasks.ImageProcessorWithMasks renderTile(final TileSpec originalTileSpec,
                                                                            final List<TransformSpec> tileTransforms,
                                                                            final double renderScale,
                                                                            final CanvasId canvasIdForClipping,
                                                                            final File saveTileFile) {

        final TileSpec tileSpec = originalTileSpec.slowClone();
        int removalCount = 0;
        while (tileSpec.hasTransforms()) {
            tileSpec.removeLastTransformSpec();
            removalCount++;
        }

        tileSpec.addTransformSpecs(tileTransforms);

        LOG.info("renderTile: removed {} existing transforms and added {} new transforms to tile {}",
                 removalCount, tileTransforms.size(), tileSpec.getTileId());

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
                                     renderScale);
        renderParameters.addTileSpec(tileSpec);
        renderParameters.initializeDerivedValues();
        renderParameters.setExcludeMask(parameters.renderWithoutMask);

        if (canvasIdForClipping != null) {
            // this is awful, but currently necessary ...
            canvasIdForClipping.setClipOffsets(renderParameters.getWidth(),
                                               renderParameters.getHeight(),
                                               parameters.featureRenderClip.clipWidth,
                                               parameters.featureRenderClip.clipHeight);
            CanvasIdWithRenderContext.clipRenderParameters(canvasIdForClipping,
                                                           parameters.featureRenderClip.clipWidth,
                                                           parameters.featureRenderClip.clipHeight,
                                                           renderParameters);
        }

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache, null);


        if (saveTileFile != null) {
            final BufferedImage targetImage = renderParameters.openTargetImage();
            final Graphics2D targetGraphics = targetImage.createGraphics();
            final BufferedImage image =
                    ByteRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                            imageProcessorWithMasks);
            targetGraphics.drawImage(image, 0, 0, null);

            try {
                Utils.saveImage(image,
                                saveTileFile,
                                renderParameters.isConvertToGray(),
                                renderParameters.getQuality());
            } catch (final Throwable t) {
                LOG.warn("renderTile: failed to save " + saveTileFile.getAbsolutePath(), t);
            }

        }

        return imageProcessorWithMasks;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTileWithTransformsClient.class);
}