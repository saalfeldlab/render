package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.apache.http.client.utils.URIBuilder;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.ByteRenderer;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Renderer;
import org.janelia.alignment.ShortRenderer;
import org.janelia.alignment.Utils;
import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.loader.ImageLoader;
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
import org.janelia.saalfeldlab.googlecloud.GoogleCloudStorageURI;
import org.janelia.saalfeldlab.googlecloud.GoogleCloudUtils;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.googlecloud.GoogleCloudStorageKeyValueAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;


/**
 * Java client for rendering individual tiles.  Images are placed in:
 * <pre>
 *   [rootDirectory]/[project]/[stack]/[runTimestamp]/[z-thousands]/[z-hundreds]/[z]/[tileId].[format]
 * </pre>
 *
 * @author Eric Trautman
 */
public class RenderTilesClient {

    public enum RenderType {
        EIGHT_BIT, SIXTEEN_BIT, ARGB
    }

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
                names = "--renderType",
                description = "How the tiles should be rendered")
        public RenderType renderType = RenderType.EIGHT_BIT;

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
                names = "--excludeAllTransforms",
                description = "Exclude all tile transforms when rendering",
                arity = 0)
        public boolean excludeAllTransforms = false;

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
                names = "--tileIdPattern",
                description = "Only include tileIds that match this pattern (filters z based and explicit tile ids)"
        )
        public String tileIdPattern;

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
        public Integer hackTransformCount;

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

    private final ImageProcessorCache imageProcessorCache;
    private final RenderDataClient renderDataClient;
    private final List<String> tileIds;
    private final String renderParametersQueryString;
    private final Map<Double, ResolvedTileSpecCollection> zToResolvedTiles;
    private final StorageBackend storageBackend;

    private RenderTilesClient(final Parameters clientParameters) {

        this.clientParameters = clientParameters;

        try {
            final URIBuilder uriBuilder = new URIBuilder(clientParameters.rootDirectory);
            final List<String> pathSegments = uriBuilder.getPathSegments();
            pathSegments.add(clientParameters.renderWeb.project);
            pathSegments.add(clientParameters.stack);

            final URI rootUri = uriBuilder.setPathSegments(pathSegments).build();
            this.storageBackend = getStorageBackend(rootUri);
            storageBackend.ensureWritableDirectory(storageBackend.root);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Invalid root directory URI: " + clientParameters.rootDirectory, e);
        }

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
        if (clientParameters.excludeAllTransforms) {
            queryParameters.append("&excludeAllTransforms=true");
        }
        // excludeSource needs to be handled locally (not supported by web service)

        this.renderParametersQueryString = queryParameters.toString();
        this.zToResolvedTiles = new HashMap<>();
    }

    private static StorageBackend getStorageBackend(URI uri) {
        final String scheme = uri.getScheme();
        if (scheme == null) {
            // Ensure that file scheme is explicit
            try {
                uri = new URI("file", uri.getAuthority(), uri.getPath(), null, null);
            } catch (final URISyntaxException e) {
                throw new IllegalArgumentException("Invalid URI: " + uri, e);
            }
        }

        if (scheme == null || scheme.equals("file")) {
            return new FileStorage(uri);
        } else if (GoogleCloudUtils.GS_SCHEME.asPredicate().test(scheme)) {
            return new CloudStorage(uri);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri.getScheme());
        }
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

        if (clientParameters.tileIdPattern != null) {
             final Pattern tileIdPattern = Pattern.compile(clientParameters.tileIdPattern);
             tileIds.removeIf(tileId -> ! tileIdPattern.matcher(tileId).matches());
            if (clientParameters.hackStack != null) {
                final Set<String> tileIdsToKeep = new HashSet<>(tileIds);
                for (final ResolvedTileSpecCollection resolvedTiles : zToResolvedTiles.values()) {
                    resolvedTiles.retainTileSpecs(tileIdsToKeep);
                }
            }
        }

        if (tileIds.isEmpty()) {
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
            if (clientParameters.hackTransformCount != null) {
                for (int i = 0; i < clientParameters.hackTransformCount; i++) {
                    tileSpec.removeLastTransformSpec();
                }
            }
            tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);
            renderParameters.x = tileSpec.getMinX();
            renderParameters.y = tileSpec.getMinY();
            renderParameters.width = (int) Math.ceil(tileSpec.getMaxX() - tileSpec.getMinX());
            renderParameters.height = (int) Math.ceil(tileSpec.getMaxY() - tileSpec.getMinY());
            renderParameters.binaryMask = true;
        }

        final TransformMeshMappingWithMasks.ImageProcessorWithMasks imageProcessorWithMasks =
                Renderer.renderImageProcessorWithMasks(renderParameters, imageProcessorCache, null);

        // Get URI for the tile file and convert to File for backwards compatibility
        final String format = clientParameters.format.toLowerCase();
        final List<String> imagePathSegments = getImagePathSegments(tileSpec, format);
        final URI imageUri = storageBackend.resolvePath(imagePathSegments);

        final BufferedImage bufferedImage;
        if ((clientParameters.renderType == RenderType.ARGB) || (! clientParameters.excludeMask)) {
            // this incorporates the mask if it exists into the rendered image
            bufferedImage = ArgbRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                    imageProcessorWithMasks);
        } else if (clientParameters.renderType == RenderType.EIGHT_BIT) {
            // this only converts the image processor and ignores the mask
            bufferedImage = ByteRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                    imageProcessorWithMasks);
        } else if (clientParameters.renderType == RenderType.SIXTEEN_BIT) {
            // this only converts the image processor and ignores the mask
            bufferedImage = ShortRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                     imageProcessorWithMasks);
        } else {
            throw new IllegalArgumentException("unsupported render type: " + clientParameters.renderType);
        }

        storageBackend.writeImage(bufferedImage, imageUri, format, renderParameters);

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

            // Use the URI string directly instead of file path
            ImageAndMask renderedImageAndMask =
                    channelSpec.getFirstMipmapImageAndMask(tileId).copyWithImage(imageUri.toString(),
                                                                                 null,
                                                                                 null);
            if (channelSpec.hasMask()) {
                if (ImageLoader.LoaderType.DYNAMIC_MASK.equals(renderedImageAndMask.getMaskLoaderType())) {
                    // if original tile spec has a dynamic mask, update the width and height to match rendered tile
                    final DynamicMaskLoader.DynamicMaskDescription description =
                            DynamicMaskLoader.parseUrl(renderedImageAndMask.getMaskUrl())
                                    .withWidthAndHeight(imageProcessorWithMasks.getWidth(),
                                                        imageProcessorWithMasks.getHeight());
                    renderedImageAndMask = renderedImageAndMask.copyWithMask(description.toString(),
                                                                             ImageLoader.LoaderType.DYNAMIC_MASK,
                                                                             null);
                } else if (imageProcessorWithMasks.mask != null) {
                    // if we rendered a new mask, save it to disk and update the tile spec reference
                    final List<String> maskPathSegments = new ArrayList<>(imagePathSegments);
                    final String maskFileName = tileSpec.getTileId() + ".mask." + format;
                    maskPathSegments.set(maskPathSegments.size() - 1, maskFileName);
                    final URI maskUri = storageBackend.resolvePath(maskPathSegments);

                    // Create mask URI based on the same parent directory
                    storageBackend.writeImage(imageProcessorWithMasks.mask.getBufferedImage(), maskUri, format, renderParameters);

                    renderedImageAndMask = renderedImageAndMask.copyWithMask(maskUri.toString(),
                                                                             null,
                                                                             null);
                }
            }

            channelSpec.putMipmap(0, renderedImageAndMask);

            // set hacked tile width and height
            hackedTileSpec.setWidth((double) imageProcessorWithMasks.ip.getWidth());
            hackedTileSpec.setHeight((double) imageProcessorWithMasks.ip.getHeight());

            if (clientParameters.hackTransformCount != null) {
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
            }

            hackedTileSpec.deriveBoundingBox(hackedTileSpec.getMeshCellSize(), true);

            // translate tile spec back to original location
            final double translateX = preHackMinX - hackedTileSpec.getMinX();
            final double translateY = preHackMinY - hackedTileSpec.getMinY();

            if ((translateX != 0.0) || (translateY != 0.0)) {
                final String translateDataString = translateX + " " + translateY;
                LOG.info("renderTile: translating hacked tile by {}", translateDataString);
                final TransformSpec translateToPreHackLocationSpec =
                        new LeafTransformSpec(TranslationModel2D.class.getName(), translateDataString);
                resolvedTiles.addTransformSpecToTile(
                        tileId,
                        translateToPreHackLocationSpec,
                        ResolvedTileSpecCollection.TransformApplicationMethod.PRE_CONCATENATE_LAST);
            }
        }
    }

    private List<String> getImagePathSegments(final TileSpec tileSpec, final String format) {
        final int zInt = tileSpec.getZ().intValue();
        final int thousands = zInt / 1000;

        // Build relative path components
        final List<String> relativePathSegments = new ArrayList<>();
        relativePathSegments.add(String.format("%03d", thousands));
        relativePathSegments.add(String.valueOf((zInt % 1000) / 100));
        relativePathSegments.add(String.valueOf(zInt));

        // Ensure the directory exists and if so append file name
        final URI parentUri = storageBackend.resolvePath(relativePathSegments);
        storageBackend.ensureWritableDirectory(parentUri);
        relativePathSegments.add(tileSpec.getTileId() + "." + format);

        return relativePathSegments;
    }


    private abstract static class StorageBackend {
        final URI root;

        StorageBackend(final URI root) {
            this.root = root;
        }

        abstract void ensureWritableDirectory(URI uri);

        abstract void writeImage(BufferedImage image, URI uri, String format, RenderParameters parameters) throws IOException;

        URI resolvePath(final List<String> relativePathSegments) {
            try {
                final URIBuilder uriBuilder = new URIBuilder(root);
                final List<String> pathSegments = uriBuilder.getPathSegments();
                pathSegments.addAll(relativePathSegments);
                return uriBuilder.setPathSegments(pathSegments).build();
            } catch (final URISyntaxException e) {
                throw new IllegalArgumentException("Failed to resolve root " + root + " with segments " + relativePathSegments, e);
            }
        }
    }

    private static class FileStorage extends StorageBackend {
        FileStorage(final URI uri) {
            super(uri);
        }

        @Override
        void ensureWritableDirectory(final URI uri) {
            FileUtil.ensureWritableDirectory(new File(uri));
        }

        @Override
        void writeImage(final BufferedImage image,
                        final URI uri,
                        final String format,
                        final RenderParameters parameters) throws IOException {
            Utils.saveImage(image, new File(uri).toString(), format, parameters.convertToGray, parameters.quality);

        }
    }

    private static class CloudStorage extends StorageBackend {
        final KeyValueAccess keyValueAccess;

        CloudStorage(final URI uri) {
            super(uri);
            this.keyValueAccess = new GoogleCloudStorageKeyValueAccess(
                    GoogleCloudUtils.createGoogleCloudStorage(null),
                    new GoogleCloudStorageURI(uri),
                    true);
        }

        @Override
        void ensureWritableDirectory(final URI uri) {
            if (!keyValueAccess.exists(uri.getPath())) {
                try {
                    keyValueAccess.createDirectories(uri.getPath());
                } catch (final IOException e) {
                    throw new RuntimeException("Could not create directory " + uri, e);
                }
            }
        }

        @Override
        void writeImage(final BufferedImage image,
                        final URI uri,
                        final String format,
                        final RenderParameters renderParameters) throws IOException {
            if (! Utils.PNG_FORMAT.equals(format)) {
                throw new IllegalArgumentException("Only PNG format is supported for cloud storage: " + format);
            }

            try (final LockedChannel lockedChannel = keyValueAccess.lockForWriting(uri.toString())) {
                final ByteArrayOutputStream oStream = new ByteArrayOutputStream();
                ImageIO.write(image, format, oStream);
                lockedChannel.newOutputStream().write(oStream.toByteArray());
                LOG.info("image written to {}", uri);
            }
        }
    }


    private static final Logger LOG = LoggerFactory.getLogger(RenderTilesClient.class);
}
