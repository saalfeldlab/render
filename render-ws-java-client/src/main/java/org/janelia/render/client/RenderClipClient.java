package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks.ImageProcessorWithMasks;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.ByteRenderer;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LayoutData;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.LayerBoundsParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering individual tiles.  Images are placed in:
 * <pre>
 *   [rootDirectory]/[project]/[stack]/[runTimestamp]/[z-thousands]/[z-hundreds]/[z]/[tileId].[format]
 * </pre>
 *
 * @author Eric Trautman
 */
public class RenderClipClient {

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
                description = "Root directory for rendered clip (e.g. /nrs/flyem/render/clip)",
                required = true)
        public String rootDirectory;

        @Parameter(
                names = "--runTimestamp",
                description = "Run timestamp to use in directory path for rendered clip (e.g. 20220830_093700).  " +
                              "Omit to use calculated timestamp.  " +
                              "Include for array jobs to ensure all clips are rendered under same base path")
        public String runTimestamp;

        @Parameter(
                names = "--singleOutputDirectory",
                description = "Render all clips into one directory rather than splitting into subdirectories",
                arity = 0)
        public boolean singleOutputDirectory = false;

        @Parameter(
                names = "--scale",
                description = "Scale for each rendered clip"
        )
        public Double scale = 1.0;

        @Parameter(
                names = "--format",
                description = "Format for rendered clips"
        )
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--render8Bit",
                description = "Render the clips as 8-bit grayscale images")
        public boolean render8Bit = true;

        @Parameter(
                names = "--filterListName",
                description = "Apply this filter list to all rendering (overrides doFilter option)"
        )
        public String filterListName;

        @Parameter(
                names = "--z",
                description = "Z values for clips to render",
                variableArity = true,
                required = true)
        public List<Double> zValues;

        @ParametersDelegate
        public LayerBoundsParameters layerBounds = new LayerBoundsParameters();

        @Parameter(
                names = "--hackStack",
                description = "If specified, create tile specs that reference the rendered clips " +
                              "and save them to this stack.")
        public String hackStack;

        @Parameter(
                names = "--completeHackStack",
                description = "Complete the hack stack after saving all tile specs",
                arity = 0)
        public boolean completeHackStack = false;

        public String getRunTimestamp() {
            if (this.runTimestamp == null) {
                final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
                this.runTimestamp = sdf.format(new Date());
            }
            return this.runTimestamp;
        }
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
                parameters.layerBounds.validate();
                if (! parameters.layerBounds.isDefined()) {
                    throw new IllegalArgumentException("layer bounds (e.g. --minX --maxX --minY --maxY) must be defined");
                }

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderClipClient client = new RenderClipClient(parameters);
                client.renderClips();
            }
        };
        clientRunner.run();
    }

    private final Parameters clientParameters;

    private final File clipDirectory;
    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;

    private RenderClipClient(final Parameters clientParameters) {

        this.clientParameters = clientParameters;

        final Path tileDirectoryPath = Paths.get(clientParameters.rootDirectory,
                                                 clientParameters.renderWeb.project,
                                                 clientParameters.stack,
                                                 clientParameters.getRunTimestamp());
        this.clipDirectory = tileDirectoryPath.toAbsolutePath().toFile();

        FileUtil.ensureWritableDirectory(this.clipDirectory);

        // set cache size to 50MB so that masks get cached but most of RAM is left for target images
        final int maxCachedPixels = 50 * 1000000;
        this.imageProcessorCache = new ImageProcessorCache(maxCachedPixels,
                                                           false,
                                                           false);

        this.renderDataClient = clientParameters.renderWeb.getDataClient();
    }

    private void renderClips()
            throws IOException {

        if (clientParameters.hackStack != null) {
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(clientParameters.stack);
            renderDataClient.setupDerivedStack(stackMetaData, clientParameters.hackStack);
            renderDataClient.deleteMipmapPathBuilder(clientParameters.hackStack);
        }

        final ResolvedTileSpecCollection hackStackTiles = new ResolvedTileSpecCollection();

        for (final Double z : clientParameters.zValues) {
            renderClip(z, hackStackTiles);
        }

        if (hackStackTiles.getTileCount() > 0) {
            renderDataClient.saveResolvedTiles(hackStackTiles, clientParameters.hackStack, null);
            if (clientParameters.completeHackStack) {
                renderDataClient.setStackState(clientParameters.hackStack, StackMetaData.StackState.COMPLETE);
            }
        }
     }

    private void renderClip(final Double z,
                            final ResolvedTileSpecCollection hackStackTiles)
            throws IOException {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final Rectangle r = clientParameters.layerBounds.toRectangle();
        final String parametersUrl = urls.getRenderParametersUrlString(clientParameters.stack,
                                                                       r.x,
                                                                       r.y,
                                                                       z,
                                                                       r.width,
                                                                       r.height,
                                                                       clientParameters.scale,
                                                                       clientParameters.filterListName);

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);

        if (renderParameters.getTileSpecs().size() == 0) {
            throw new IOException("no tiles found for " + parametersUrl);
        }

        final ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache, null);

        final File clipFile = getClipFile(z);

        final BufferedImage bufferedImage;
        if (clientParameters.render8Bit) {
            bufferedImage = ByteRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                    imageProcessorWithMasks);
        } else {
            bufferedImage = ArgbRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                    imageProcessorWithMasks);
        }

        Utils.saveImage(bufferedImage,
                        clipFile,
                        renderParameters.isConvertToGray(),
                        renderParameters.getQuality());

        if (clientParameters.hackStack != null) {
            final TileSpec tileSpec = new TileSpec();
            tileSpec.setTileId("clip." + z);
            tileSpec.setZ(z);
            tileSpec.setLayout(new LayoutData(String.valueOf(z),
                                              null,
                                              null,
                                              0,
                                              0,
                                              0.0,
                                              0.0,
                                              null));
            tileSpec.setWidth((double) bufferedImage.getWidth());
            tileSpec.setHeight((double) bufferedImage.getHeight());

            final ChannelSpec channelSpec = new ChannelSpec();
            channelSpec.putMipmap(0, new ImageAndMask(clipFile, null));
            tileSpec.addChannel(channelSpec);
            final TransformSpec transformSpec = new LeafTransformSpec(AffineModel2D.class.getName(),
                                                                      "1 0 0 1 0 0");
            tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

            hackStackTiles.addTileSpecToCollection(tileSpec);
        }

    }

    private File getClipFile(final Double z) {
        final int zInt = z.intValue();
        final File parentDirectory;
        if (clientParameters.singleOutputDirectory) {
            parentDirectory = clipDirectory;
        } else {
            final File thousandsDir = new File(clipDirectory, String.format("%03d", (zInt / 1000)));
            parentDirectory = new File(thousandsDir, String.valueOf(((zInt % 1000) / 100)));
        }

        FileUtil.ensureWritableDirectory(parentDirectory);

        return new File(parentDirectory,
                        "clip_" + String.format("%06d", zInt) + "." + clientParameters.format.toLowerCase());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderClipClient.class);
}
