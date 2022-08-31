package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering individual tiles.
 *
 * Images are placed in:
 * <pre>
 *   [rootDirectory]/[project]/[stack]/[runTimestamp]/[z-thousands]/[z-hundreds]/[z]/[tileId].[format]
 * </pre>
 *
 * @author Eric Trautman
 */
public class RenderTilesClient {

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
                names = "--runTimestamp",
                description = "Run timestamp to use in directory path for rendered tiles (e.g. 20220830_093700).  " +
                              "Omit to use calculated timestamp.  " +
                              "Include for array jobs to ensure all tiles are rendered under same base path")
        public String runTimestamp;

        @Parameter(
                names = "--scale",
                description = "Scale for each rendered tile"
        )
        public Double scale = 1.0;

        @Parameter(
                names = "--format",
                description = "Format for rendered tiles"
        )
        public String format = Utils.PNG_FORMAT;

        @Parameter(
                names = "--doFilter",
                description = "Use ad hoc filter to support alignment",
                arity = 0)
        public boolean doFilter = false;

        @Parameter(
                names = "--filterListName",
                description = "Apply this filter list to all rendering (overrides doFilter option)"
        )
        public String filterListName;

        @Parameter(
                names = "--channels",
                description = "Specify channel(s) and weights to render (e.g. 'DAPI' or 'DAPI__0.7__TdTomato__0.3')"
        )
        public String channels;

        @Parameter(
                names = "--fillWithNoise",
                description = "Fill image with noise before rendering to improve point match derivation",
                arity = 0)
        public boolean fillWithNoise = false;

        @Parameter(
                names = "--maxIntensity",
                description = "Max intensity to render image"
        )
        public Integer maxIntensity;

        @Parameter(
                names = "--minIntensity",
                description = "Min intensity to render image"
        )
        public Integer minIntensity;

        @Parameter(
                names = "--excludeMask",
                description = "Exclude tile masks when rendering",
                arity = 0)
        public boolean excludeMask = false;

        @Parameter(
                names = "--renderMaskOnly",
                description = "Only render transformed mask for each tile",
                arity = 0)
        public boolean renderMaskOnly = false;

        @Parameter(
                names = "--z",
                description = "Z values for tiles to render",
                variableArity = true)
        public List<Double> zValues;

        @Parameter(
                names = "--tileIds",
                description = "Explicit IDs for tiles to render",
                variableArity = true
        )
        public List<String> tileIds;

        @Parameter(
                names = "--hackStack",
                description = "If specified, create tile specs that reference the rendered tiles " +
                              "and save them to this stack.  The hackTransformCount determines how " +
                              "many transforms are rendered and how many are included in each tile spec.")
        public String hackStack;

        @Parameter(
                names = "--hackTransformCount",
                description = "Number of transforms to remove from the end of each tile spec's list " +
                              "during rendering but then include in the hack stack's tile specs")
        public Integer hackTransformCount = 1;

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

                LOG.info("runClient: entry, parameters={}", parameters);

                final RenderTilesClient client = new RenderTilesClient(parameters);
                client.collectTileInfo();
                client.renderTiles();
            }
        };
        clientRunner.run();
    }

    private final Parameters clientParameters;

    private final File tileDirectory;
    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;
    private final List<String> tileIds;
    private final String renderParametersQueryString;
    private final Map<Double, ResolvedTileSpecCollection> zToResolvedTiles;

    private RenderTilesClient(final Parameters clientParameters) {

        this.clientParameters = clientParameters;

        final Path tileDirectoryPath = Paths.get(clientParameters.rootDirectory,
                                                 clientParameters.renderWeb.project,
                                                 clientParameters.stack,
                                                 clientParameters.getRunTimestamp());
        this.tileDirectory = tileDirectoryPath.toAbsolutePath().toFile();

        FileUtil.ensureWritableDirectory(this.tileDirectory);

        // set cache size to 50MB so that masks get cached but most of RAM is left for target images
        final int maxCachedPixels = 50 * 1000000;
        this.imageProcessorCache = new ImageProcessorCache(maxCachedPixels,
                                                           false,
                                                           false);

        this.renderDataClient = clientParameters.renderWeb.getDataClient();

        this.tileIds = new ArrayList<>();

        final StringBuilder queryParameters = new StringBuilder();
        queryParameters.append("?scale=").append(clientParameters.scale);
        if (clientParameters.doFilter) {
            queryParameters.append("&doFilter=true");
        }
        if (clientParameters.filterListName != null) {
            queryParameters.append("&filterListName=").append(clientParameters.filterListName);
        }
        if (clientParameters.channels != null) {
            if (clientParameters.hackStack != null) {
                throw new IllegalArgumentException("explicit channels cannot be specified when creating a hack stack");
            }
            queryParameters.append("&channels=").append(clientParameters.channels);
        }
        if (clientParameters.fillWithNoise) {
            queryParameters.append("&fillWithNoise=true");
        }
        if (clientParameters.minIntensity != null) {
            queryParameters.append("&minIntensity=").append(clientParameters.minIntensity);
        }
        if (clientParameters.maxIntensity != null) {
            queryParameters.append("&maxIntensity=").append(clientParameters.maxIntensity);
        }
        if (clientParameters.excludeMask) {
            queryParameters.append("&excludeMask=true");
        }
        // excludeSource needs to be handled locally (not supported by web service)

        this.renderParametersQueryString = queryParameters.toString();
        this.zToResolvedTiles = new HashMap<>();
    }

    private void collectTileInfo()
            throws IOException {

        if (clientParameters.zValues != null) {
            for (final Double z : clientParameters.zValues) {
                if (clientParameters.hackStack == null) {
                    final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(clientParameters.stack, z);
                    tileBoundsList.forEach(tileBounds -> this.tileIds.add(tileBounds.getTileId()));
                } else {
                    final ResolvedTileSpecCollection resolvedTiles =
                            renderDataClient.getResolvedTiles(clientParameters.stack, z);
                    zToResolvedTiles.put(z, resolvedTiles);
                    resolvedTiles.getTileSpecs().forEach(tileSpec -> this.tileIds.add(tileSpec.getTileId()));
                }
            }
        }

        if (clientParameters.tileIds != null) {
            if (clientParameters.hackStack != null) {
                throw new IllegalArgumentException("explicit tile ids cannot be specified when creating a hack stack");
            }
            tileIds.addAll(clientParameters.tileIds);
        }

        if (tileIds.size() == 0) {
            throw new IllegalArgumentException("There are no tiles to render!");
        }

    }

    private void renderTiles()
            throws IOException {

        if (clientParameters.hackStack != null) {
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(clientParameters.stack);
            renderDataClient.setupDerivedStack(stackMetaData, clientParameters.hackStack);
            renderDataClient.deleteMipmapPathBuilder(clientParameters.hackStack);
        }

        for (final String tileId : tileIds) {
            renderTile(tileId);
        }

        if (clientParameters.hackStack != null) {
            for (final Double z : zToResolvedTiles.keySet().stream().sorted().collect(Collectors.toList())) {
                renderDataClient.saveResolvedTiles(zToResolvedTiles.get(z), clientParameters.hackStack, z);
            }
            if (clientParameters.completeHackStack) {
                renderDataClient.setStackState(clientParameters.hackStack, StackMetaData.StackState.COMPLETE);
            }
        }
    }

    private void renderTile(final String tileId)
            throws IOException {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String parametersUrl = urls.getTileUrlString(clientParameters.stack, tileId) + "/render-parameters" +
                                     renderParametersQueryString;

        final RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
        final TileSpec tileSpec = renderParameters.getTileSpecs().get(0);

        if (clientParameters.renderMaskOnly) {

            // this hack conversion is needed to simplify working with channel specs below
            final String firstChannelName = tileSpec.getFirstChannelName();
            if (firstChannelName == null) {
                tileSpec.convertLegacyToChannel(null);
            }

            for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {
                final Map.Entry<Integer, ImageAndMask> firstEntry = channelSpec.getFirstMipmapEntry();
                final ImageAndMask imageAndMask = firstEntry.getValue();
                if (imageAndMask.hasMask()) {
                    channelSpec.putMipmap(firstEntry.getKey(), imageAndMask.maskAsImage());
                } else {
                    throw new IOException("Tile " + tileId + " does not have a mask to render.");
                }
            }

        }

        if (clientParameters.hackStack != null) {
            for (int i = 0; i < clientParameters.hackTransformCount; i++) {
                tileSpec.removeLastTransformSpec();
            }
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
            renderParameters.x = tileSpec.getMinX();
            renderParameters.y = tileSpec.getMinY();
            renderParameters.width = (int) Math.ceil(tileSpec.getMaxX() - tileSpec.getMinX());
            renderParameters.height = (int) Math.ceil(tileSpec.getMaxY() - tileSpec.getMinY());
            renderParameters.binaryMask = true;
        }

        final File tileFile = getTileFile(tileSpec);

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache, tileFile);

        if (clientParameters.hackStack != null) {
            final ResolvedTileSpecCollection resolvedTiles = zToResolvedTiles.get(tileSpec.getZ());
            final TileSpec hackedTileSpec = resolvedTiles.getTileSpec(tileId);
            final double preHackMinX = hackedTileSpec.getMinX();
            final double preHackMinY = hackedTileSpec.getMinY();

            // set hacked mipmap
            final List<ChannelSpec> allChannels = hackedTileSpec.getAllChannels();
            if (allChannels.size() != 1) {
                throw new IllegalArgumentException("hack stack tiles should have only one channel but tile " +
                                                   tileId + " has " + allChannels.size() + " channels");
            }
            final ChannelSpec channelSpec = allChannels.get(0);
            String maskPath = null;
            if (imageProcessorWithMasks.mask != null) {
                final String maskFileName = tileFile.getName().replace(clientParameters.format,
                                                                       "mask." + clientParameters.format);
                final File maskFile = new File(tileFile.getParentFile().getAbsolutePath(), maskFileName);
                maskPath = maskFile.getAbsolutePath();
                Utils.saveImage(imageProcessorWithMasks.mask.getBufferedImage(),
                                maskPath,
                                clientParameters.format,
                                renderParameters.convertToGray,
                                renderParameters.quality);
            }

            channelSpec.putMipmap(0, new ImageAndMask(tileFile.getAbsolutePath(), maskPath));

            // set hacked tile width and height
            hackedTileSpec.setWidth((double) imageProcessorWithMasks.ip.getWidth());
            hackedTileSpec.setHeight((double) imageProcessorWithMasks.ip.getHeight());

            // set hacked tile transforms
            final Deque<TransformSpec> transformStack = new ArrayDeque<>();
            final ListTransformSpec flattenedList = new ListTransformSpec();
            hackedTileSpec.getTransforms().flatten(flattenedList);
            for (int i = 0; i < clientParameters.hackTransformCount; i++) {
                transformStack.push(flattenedList.getLastSpec());
                flattenedList.removeLastSpec();
            }

            final ListTransformSpec hackedTransformList = new ListTransformSpec();
            transformStack.forEach(hackedTransformList::addSpec);
            hackedTileSpec.setTransforms(hackedTransformList);

            hackedTileSpec.deriveBoundingBox(hackedTileSpec.getMeshCellSize(), true);

            // translate tile spec back to original location
            final double translateX = preHackMinX - hackedTileSpec.getMinX();
            final double translateY = preHackMinY - hackedTileSpec.getMinY();
            final String translateDataString = translateX + " " + translateY;
            LOG.info("renderTile: translating hacked tile by " + translateDataString);
            final TransformSpec translateToPreHackLocationSpec =
                    new LeafTransformSpec(TranslationModel2D.class.getName(), translateDataString);
            resolvedTiles.addTransformSpecToTile(
                    tileId,
                    translateToPreHackLocationSpec,
                    ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST);
        }
    }

    private File getTileFile(final TileSpec tileSpec) {

        final int zInt = tileSpec.getZ().intValue();
        final int thousands = zInt / 1000;
        final File thousandsDir = new File(tileDirectory, String.format("%03d", thousands));

        final int hundreds = (zInt % 1000) / 100;
        final File hundredsDirectory = new File(thousandsDir, String.valueOf(hundreds));

        final File parentDirectory = new File(hundredsDirectory, String.valueOf(zInt));

        FileUtil.ensureWritableDirectory(parentDirectory);

        return new File(parentDirectory, tileSpec.getTileId() + "." + clientParameters.format.toLowerCase());
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTilesClient.class);
}
