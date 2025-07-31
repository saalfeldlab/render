package org.janelia.render.client.tile;

import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

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
import org.janelia.alignment.filter.FilterFactory;
import org.janelia.alignment.filter.FilterSpec;
import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileRenderParameters;
import org.janelia.saalfeldlab.googlecloud.GoogleCloudStorageURI;
import org.janelia.saalfeldlab.googlecloud.GoogleCloudUtils;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.googlecloud.GoogleCloudStorageKeyValueAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.Nonnull;


/**
 * Java client for rendering individual tiles.  Images are placed in:
 * <pre>
 *   [rootDirectory]/[project]/[stack]/[runTimestamp]/[z-thousands]/[z-hundreds]/[z]/[tileId].[format]
 * </pre>
 *
 * @author Eric Trautman
 */
public class RenderTilesClient {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public TileRenderParameters tileRender = new TileRenderParameters();
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

                final RenderDataClient multiProjectDataClient = parameters.multiProject.getDataClient();
                final List<StackWithZValues> stackWithZValuesList = parameters.multiProject.buildListOfStackWithAllZ();
                for (final StackWithZValues stackWithZValues : stackWithZValuesList) {

                    final StackId stackId = stackWithZValues.getStackId();
                    final RenderDataClient projectDataClient =
                            multiProjectDataClient.buildClientForProject(stackId.getProject());

                    final RenderTilesClient client = new RenderTilesClient(projectDataClient,
                                                                           stackId.getStack(),
                                                                           parameters.tileRender);
                    client.setupHackStackAsNeeded();
                    client.renderTiles(stackWithZValues.getzValues());
                    client.completeHackStackAsNeeded();
                }
            }
        };
        clientRunner.run();
    }

    private final String stack;
    private final TileRenderParameters tileRender;

    private final RenderDataClient renderDataClient;
    private final String renderParametersQueryString;
    private final Map<Double, ResolvedTileSpecCollection> zToResolvedTiles;
    private final StorageBackend storageBackend;
    private final List<FilterSpec> filterSpecList;

    public RenderTilesClient(final RenderDataClient projectDataClient,
                             final String stack,
                             final TileRenderParameters tileRender) {

        this.stack = stack;
        this.tileRender = tileRender;

        if ((tileRender.hackTransformCount != null) && (tileRender.renderTileImagesLocally)) {
            throw new IllegalArgumentException("--hackTransformCount option cannot be used with --renderTileImagesLocally");
        }

        try {
            final URIBuilder uriBuilder = new URIBuilder(tileRender.rootDirectory);
            final List<String> pathSegments = uriBuilder.getPathSegments();
            pathSegments.add(projectDataClient.getProject());
            pathSegments.add(stack);
            pathSegments.add(tileRender.getRunTimestamp());

            final URI rootUri = uriBuilder.setPathSegments(pathSegments).build();
            this.storageBackend = StorageBackend.create(rootUri);
            storageBackend.validateFormat(tileRender.format);
            storageBackend.ensureWritableDirectory(storageBackend.root);
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Invalid root directory URI: " + tileRender.rootDirectory, e);
        }

        this.renderDataClient = projectDataClient;

        final StringBuilder queryParameters = new StringBuilder();
        queryParameters.append("?scale=").append(tileRender.scale);

        List<FilterSpec> filterSpecList = null;
        if (tileRender.filterListPath == null) {

            if (tileRender.doFilter) {
                queryParameters.append("&doFilter=true");
            }
            if (tileRender.filterListName != null) {
                queryParameters.append("&filterListName=").append(tileRender.filterListName);
            }

        } else {

            if ((tileRender.doFilter) || (tileRender.filterListName != null)) {
                throw new IllegalArgumentException(
                        "--filterListPath option cannot be used with --doFilter or --filterListName");
            }

            final File filterFile = new File(tileRender.filterListPath);
            if (filterFile.exists()) {
                try {
                    final FilterFactory factory = FilterFactory.fromJson(new FileReader(filterFile));
                    final List<String> filterListNames = factory.getSortedFilterListNames();
                    if (filterListNames.size() != 1) {
                        throw new IllegalArgumentException(
                                "The filterListPath file " + filterFile.getAbsolutePath() + " contains " +
                                filterListNames.size() + " lists but must contain one and only one list");
                    }
                    filterSpecList = factory.getFilterList(filterListNames.get(0));
                } catch(final IOException ioe) {
                    throw new IllegalArgumentException("Failed to read filterListPath " + filterFile.getAbsolutePath(),
                                                       ioe);
                }
            }

        }
        this.filterSpecList = filterSpecList;

        if (tileRender.channels != null) {
            if (tileRender.hackStack != null) {
                throw new IllegalArgumentException("explicit channels cannot be specified when creating a hack stack");
            }
            queryParameters.append("&channels=").append(tileRender.channels);
        }
        if (tileRender.fillWithNoise) {
            queryParameters.append("&fillWithNoise=true");
        }
        if (tileRender.minIntensity != null) {
            queryParameters.append("&minIntensity=").append(tileRender.minIntensity);
        }
        if (tileRender.maxIntensity != null) {
            queryParameters.append("&maxIntensity=").append(tileRender.maxIntensity);
        }
        if (tileRender.excludeMask) {
            queryParameters.append("&excludeMask=true");
        }
        if (tileRender.excludeAllTransforms) {
            queryParameters.append("&excludeAllTransforms=true");
        }
        // excludeSource needs to be handled locally (not supported by web service)

        this.renderParametersQueryString = queryParameters.toString();
        this.zToResolvedTiles = new HashMap<>();
    }

    public void renderTiles(final List<Double> zValues)
            throws IOException {

        // set cache size to 50MB so that masks get cached but most RAM is left for target images
        final int maxCachedPixels = 50 * 1000000;
        final ImageProcessorCache imageProcessorCache = new ImageProcessorCache(maxCachedPixels,
                                                                                false,
                                                                                false);

        final List<String> tileIds = buildTileIdsList(zValues);
        for (final String tileId : tileIds) {
            renderTile(tileId, imageProcessorCache);
        }

        if (tileRender.hackStack != null) {
            for (final Double z : zValues) {
                renderDataClient.saveResolvedTiles(zToResolvedTiles.get(z), tileRender.hackStack, z);
            }
        }
    }

    @Nonnull
    private List<String> buildTileIdsList(final List<Double> zValues)
            throws IOException {

        final List<String> tileIds = new ArrayList<>();

        if (zValues != null) {
            for (final Double z : zValues) {
                if (tileRender.hackStack == null) {
                    final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(stack, z);
                    tileBoundsList.forEach(tileBounds -> tileIds.add(tileBounds.getTileId()));
                } else {
                    final ResolvedTileSpecCollection resolvedTiles =
                            renderDataClient.getResolvedTiles(stack, z);
                    zToResolvedTiles.put(z, resolvedTiles);
                    resolvedTiles.getTileSpecs().forEach(tileSpec -> tileIds.add(tileSpec.getTileId()));
                }
            }
        }

        if (tileRender.tileIds != null) {
            if (tileRender.hackStack != null) {
                throw new IllegalArgumentException("explicit tile ids cannot be specified when creating a hack stack");
            }
            tileIds.addAll(tileRender.tileIds);
        }

        if (tileRender.tileIdPattern != null) {
             final Pattern tileIdPattern = Pattern.compile(tileRender.tileIdPattern);
             tileIds.removeIf(tileId -> ! tileIdPattern.matcher(tileId).matches());
            if (tileRender.hackStack != null) {
                final Set<String> tileIdsToKeep = new HashSet<>(tileIds);
                for (final ResolvedTileSpecCollection resolvedTiles : zToResolvedTiles.values()) {
                    resolvedTiles.retainTileSpecs(tileIdsToKeep);
                }
            }
        }

        if (tileIds.isEmpty()) {
            throw new IllegalArgumentException("There are no tiles to render!");
        }

        return tileIds;
    }

    public void setupHackStackAsNeeded()
            throws IOException {
        if (tileRender.hackStack != null) {
            final StackMetaData stackMetaData = renderDataClient.getStackMetaData(stack);
            renderDataClient.setupDerivedStack(stackMetaData, tileRender.hackStack);
            renderDataClient.deleteMipmapPathBuilder(tileRender.hackStack);
        }
    }

    public void setupStorageDirectories()
            throws IOException {
        final List<Double> zValues = renderDataClient.getStackZValues(stack);
        for (final Double z : zValues) {
            final List<String> relativePathSegments = getImageParentPathSegments(z);
            final URI parentUri = storageBackend.resolvePath(relativePathSegments);
            // including this log statement seemed to help us avoid Google rate limit exceptions
            LOG.info("setupStorageDirectories: ensuring writable directory for z {} at {}",
                     z, parentUri);
            storageBackend.ensureWritableDirectory(parentUri);
        }
    }

    public void completeHackStackAsNeeded()
            throws IOException {
        if (tileRender.completeHackStack && (tileRender.hackStack != null)){
            renderDataClient.setStackState(tileRender.hackStack, StackMetaData.StackState.COMPLETE);
        }
    }

    private void renderTile(final String tileId,
                            final ImageProcessorCache imageProcessorCache)
            throws IOException {

        final RenderWebServiceUrls urls = renderDataClient.getUrls();
        final String parametersUrl = urls.getTileUrlString(stack, tileId) + "/render-parameters" +
                                     renderParametersQueryString;

        RenderParameters renderParameters = RenderParameters.loadFromUrl(parametersUrl);
        final TileSpec tileSpec = renderParameters.getTileSpecs().get(0);

        if (tileRender.renderTileImagesLocally) {
            final String imageUrl = tileSpec.getFirstMipmapEntry().getValue().getImageUrl();
            final String convertedUrl = Utils.replaceBasenameInImageUrlStringWithRenderParameters(imageUrl);
            LOG.info("renderTile: to force local render, converted image URL {} to {}", imageUrl, convertedUrl);

            renderParameters = RenderParameters.loadFromUrl(convertedUrl);
        }

        if (filterSpecList != null) {
            tileSpec.setFilterSpec(filterSpecList.get(0));
            for (int i = 1; i < filterSpecList.size(); i++) {
                tileSpec.addFilterSpec(filterSpecList.get(i));
            }
        }

        if (tileRender.renderMaskOnly) {

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

        if ((tileRender.hackStack != null) && (! tileRender.renderTileImagesLocally)) {
            if (tileRender.hackTransformCount != null) {
                for (int i = 0; i < tileRender.hackTransformCount; i++) {
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
        final String format = tileRender.format.toLowerCase();
        final List<String> imagePathSegments = getImagePathSegments(tileSpec, format);
        final URI imageUri = storageBackend.resolvePath(imagePathSegments);

        final BufferedImage bufferedImage;
        if ((tileRender.renderType == TileRenderParameters.RenderType.ARGB) || (! tileRender.excludeMask)) {
            // this incorporates the mask if it exists into the rendered image
            bufferedImage = ArgbRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                    imageProcessorWithMasks);
        } else if (tileRender.renderType == TileRenderParameters.RenderType.EIGHT_BIT) {
            // this only converts the image processor and ignores the mask
            bufferedImage = ByteRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                    imageProcessorWithMasks);
        } else if (tileRender.renderType == TileRenderParameters.RenderType.SIXTEEN_BIT) {
            // this only converts the image processor and ignores the mask
            bufferedImage = ShortRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParameters,
                                                                                     imageProcessorWithMasks);
        } else {
            throw new IllegalArgumentException("unsupported render type: " + tileRender.renderType);
        }

        final String imageUrl = storageBackend.writeImage(bufferedImage, imageUri, format, renderParameters);

        if (tileRender.hackStack != null) {
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

            // Use the URI string directly instead of the file path
            ImageAndMask renderedImageAndMask = channelSpec
                    .getFirstMipmapImageAndMask(tileId)
                    .copyWithImage(imageUrl, null, null);

            if (channelSpec.hasMask()) {
                if (ImageLoader.LoaderType.DYNAMIC_MASK.equals(renderedImageAndMask.getMaskLoaderType())) {
                    // if the original tile spec has a dynamic mask, update the width and height to match the rendered tile
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
                    final String maskUrl = storageBackend.writeImage(imageProcessorWithMasks.mask.getBufferedImage(), maskUri, format, renderParameters);

                    renderedImageAndMask = renderedImageAndMask.copyWithMask(maskUrl, null, null);
                }
            }

            channelSpec.putMipmap(0, renderedImageAndMask);

            // set hacked tile width and height
            hackedTileSpec.setWidth((double) imageProcessorWithMasks.ip.getWidth());
            hackedTileSpec.setHeight((double) imageProcessorWithMasks.ip.getHeight());

            if (tileRender.hackTransformCount != null) {
                // set hacked tile transforms
                final Deque<TransformSpec> transformStack = new ArrayDeque<>();
                final ListTransformSpec flattenedList = new ListTransformSpec();
                hackedTileSpec.getTransforms().flatten(flattenedList);
                for (int i = 0; i < tileRender.hackTransformCount; i++) {
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

    private List<String> getImageParentPathSegments(final Double z) {
        final int zInt = z.intValue();
        final int thousands = zInt / 1000;

        // Build relative path components
        final List<String> relativePathSegments = new ArrayList<>();
        relativePathSegments.add(String.format("%03d", thousands));
        relativePathSegments.add(String.valueOf((zInt % 1000) / 100));
        relativePathSegments.add(String.valueOf(zInt));

        return relativePathSegments;
    }

    private List<String> getImagePathSegments(final TileSpec tileSpec,
                                              final String format) {
        final List<String> relativePathSegments = getImageParentPathSegments(tileSpec.getZ());

        // Ensure the directory exists and if so append file name
        final URI parentUri = storageBackend.resolvePath(relativePathSegments);
        storageBackend.ensureWritableDirectory(parentUri);
        relativePathSegments.add(tileSpec.getTileId() + "." + format);

        return relativePathSegments;
    }


    /**
     * An abstract class that provides a common interface for different storage backends.
     * It handles the creation of the appropriate backend based on the URI scheme.
     */
    private abstract static class StorageBackend {
        final URI root;

        StorageBackend(final URI uri) {
            this.root = uri;
        }

        static StorageBackend create(URI uri) {
            String scheme = uri.getScheme();
            if (scheme == null) {
                // Ensure that file scheme is explicit
                try {
                    uri = new URI("file", uri.getAuthority(), uri.getPath(), null, null);
                } catch (final URISyntaxException e) {
                    throw new IllegalArgumentException("Invalid URI: " + uri, e);
                }
            }

            scheme = uri.getScheme();
            if (scheme.equals("file")) {
                return new FileStorage(uri);
            } else if (GoogleCloudUtils.GS_SCHEME.asPredicate().test(scheme)) {
                return new CloudStorage(uri);
            } else {
                throw new IllegalArgumentException("Unsupported URI scheme: " + scheme);
            }
        }

        abstract void ensureWritableDirectory(URI uri);

        void validateFormat(final String format) throws IllegalArgumentException {
            final Set<String> lowerCaseFormatNames = Arrays.stream(ImageIO.getWriterFormatNames())
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            if (! lowerCaseFormatNames.contains(format.toLowerCase())) {
                throw new IllegalArgumentException(
                        "Unsupported image format '" + format + "'. Supported formats are " +
                        lowerCaseFormatNames.stream().sorted().collect(Collectors.joining(", ")));
            }
        }

        abstract String writeImage(BufferedImage image, URI uri, String format, RenderParameters parameters) throws IOException;

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

    /**
     * A concrete implementation of StorageBackend that handles file system storage.
     */
    private static class FileStorage extends StorageBackend {
        FileStorage(final URI uri) {
            super(uri);
        }

        @Override
        void ensureWritableDirectory(final URI uri) {
            FileUtil.ensureWritableDirectory(new File(uri));
        }

        @Override
        String writeImage(final BufferedImage image,
                        final URI uri,
                        final String format,
                        final RenderParameters parameters) throws IOException {
            final String fullPath = new File(uri).toString();
            Utils.saveImage(image, fullPath, format, parameters.convertToGray, parameters.quality);
            return fullPath;
        }
    }

    /**
     * A concrete implementation of StorageBackend that handles cloud storage (only Google Cloud Storage so far).
     */
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
        String writeImage(final BufferedImage image,
                        final URI uri,
                        final String format,
                        final RenderParameters renderParameters) throws IOException {

            // TODO: render parameters are currently ignored, so the behavior might differ from the file system version!
            try (final LockedChannel lockedChannel = keyValueAccess.lockForWriting(uri.getPath())) {
                final ByteArrayOutputStream oStream = new ByteArrayOutputStream();
                ImageIO.write(image, format, oStream);
                lockedChannel.newOutputStream().write(oStream.toByteArray());
                LOG.info("image written to {}", uri);
            }

            // This yields the public URL for the image
            return "https://storage.googleapis.com" + uri.toString().substring(4);
        }
    }


    private static final Logger LOG = LoggerFactory.getLogger(RenderTilesClient.class);
}
