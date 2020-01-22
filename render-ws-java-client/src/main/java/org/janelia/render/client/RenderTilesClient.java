package org.janelia.render.client;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.alignment.util.RenderWebServiceUrls;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for rendering individual tiles.
 * Images are placed in: [rootDirectory]/[project]/[stack]/[runtime]/[z-thousands]/[z-hundreds]/[z]/[tileId].[format]
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
                description = "Root directory for rendered layers (e.g. /nrs/flyem/render/tiles)",
                required = true)
        public String rootDirectory;

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
                client.collectTileIds();
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

    private RenderTilesClient(final Parameters clientParameters) {

        this.clientParameters = clientParameters;

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final Path tileDirectoryPath = Paths.get(clientParameters.rootDirectory,
                                                 clientParameters.renderWeb.project,
                                                 clientParameters.stack,
                                                 sdf.format(new Date()));
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
    }

    private void collectTileIds()
            throws IOException {

        if (clientParameters.zValues != null) {
            for (final Double z : clientParameters.zValues) {
                final List<TileBounds> tileBoundsList = renderDataClient.getTileBounds(clientParameters.stack, z);
                tileBoundsList.forEach(tileBounds -> this.tileIds.add(tileBounds.getTileId()));
            }
        }

        if (clientParameters.tileIds != null) {
            tileIds.addAll(clientParameters.tileIds);
        }

        if (tileIds.size() == 0) {
            throw new IllegalArgumentException("There are no tiles to render!");
        }

    }

    private void renderTiles()
            throws IOException {

        for (final String tileId : tileIds) {
            renderTile(tileId);
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
                    final ImageAndMask maskAsImage = new ImageAndMask(imageAndMask.getMaskUrl(), null);
                    channelSpec.putMipmap(firstEntry.getKey(), maskAsImage);
                } else {
                    throw new IOException("Tile " + tileId + " does not have a mask to render.");
                }
            }

        }

        final File tileFile = getTileFile(tileSpec);

        final BufferedImage tileImage = renderParameters.openTargetImage();

        ArgbRenderer.render(renderParameters, tileImage, imageProcessorCache);

        Utils.saveImage(tileImage, tileFile.getAbsolutePath(), clientParameters.format, true, 0.85f);
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
