package org.janelia.render.client.spark.destreak;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.http.client.utils.URIBuilder;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.ShortRenderer;
import org.janelia.alignment.Utils;
import org.janelia.alignment.destreak.SecondChannelStreakCorrector;
import org.janelia.alignment.loader.DynamicMaskLoader;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.FileUtil;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.ImagePlus;
import ij.process.ImageProcessor;

/**
 * Spark client for applying streak correction to tiles using both channels of microscopy acquisition.
 * Images are placed in:
 * <pre>
 *   [outputDirectory]/[project]/[stack]/[runTimestamp]/[z-thousands]/[z-hundreds]/[z]/[tileId].png
 * </pre>
 */
public class StreakCorrectionClient implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public StreakCorrectorParameters streakCorrector = new StreakCorrectorParameters();

        @Parameter(
                names = "--inputStack",
                description = "Input stack name",
                required = true)
        public String inputStack;

        @Parameter(
                names = "--outputStack",
                description = "Output stack name",
                required = true)
        public String outputStack;

        @Parameter(
                names = "--outputDirectory",
                description = "Output directory for rendered images",
                required = true)
        public String outputDirectory;

        @Parameter(
                names = "--runTimestamp",
                description = "Run timestamp for organizing output (default: current time in YYYYMMDD_hhmmss format)")
        public String runTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        @Parameter(
                names = "--completeStack",
                description = "Mark output stack as COMPLETE after processing",
                arity = 0)
        public boolean completeStack = false;

        @Parameter(
                names = "--zValues",
                description = "Explicit list of z values to process (if not specified, all z values will be processed)",
                variableArity = true)
        public List<Double> zValues;

        public Parameters() {}
    }


    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final StreakCorrectionClient client = new StreakCorrectionClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }


    /** Empty constructor required for serialization. */
    public StreakCorrectionClient() {}

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
            correctStreaks(sparkContext, clientParameters);
        }
    }

    private void correctStreaks(final JavaSparkContext sparkContext,
                                final Parameters clientParameters)
            throws IOException {

        LOG.info("correctStreaks: entry, clientParameters={}", clientParameters);
        final RenderDataClient renderDataClient = clientParameters.renderWeb.getDataClient();

        // Setup output stack
        setupOutputStack(renderDataClient, clientParameters);

        // Get z values to process
        final List<Double> zValuesToProcess = getZValuesToProcess(renderDataClient, clientParameters);
        LOG.info("correctStreaks: processing {} z values", zValuesToProcess.size());

        // Parallelize over z values
        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValuesToProcess);

        rddZValues.foreach(z -> {
            LogUtilities.setupExecutorLog4j("z_" + z);
            final RenderDataClient localRenderDataClient = clientParameters.renderWeb.getDataClient();
            correctZLayer(localRenderDataClient, clientParameters, z);
        });
        LOG.info("correctStreaks: completed processing for {} z layers", zValuesToProcess.size());

        // Complete stack if requested
        if (clientParameters.completeStack) {
            renderDataClient.setStackState(clientParameters.outputStack, StackMetaData.StackState.COMPLETE);
            LOG.info("correctStreaks: marked stack {} as COMPLETE", clientParameters.outputStack);
        }

        LOG.info("correctStreaks: exit");
    }

    private void setupOutputStack(final RenderDataClient renderDataClient,
                                  final Parameters clientParameters)
            throws IOException {

        final StackMetaData inputStackMetaData = renderDataClient.getStackMetaData(clientParameters.inputStack);
        renderDataClient.setupDerivedStack(inputStackMetaData, clientParameters.outputStack);
        renderDataClient.deleteMipmapPathBuilder(clientParameters.outputStack);

        LOG.info("setupOutputStack: created output stack {}", clientParameters.outputStack);
    }

    private List<Double> getZValuesToProcess(final RenderDataClient renderDataClient,
                                             final Parameters clientParameters)
            throws IOException {

        if (clientParameters.zValues != null && !clientParameters.zValues.isEmpty()) {
            return new ArrayList<>(clientParameters.zValues);
        } else {
            return renderDataClient.getStackZValues(clientParameters.inputStack);
        }
    }

    private void correctZLayer(final RenderDataClient renderDataClient,
                               final Parameters clientParameters,
                               final Double z)
            throws IOException {

        // Process each tile for this z layer
        LOG.info("correctZLayer: processing z={}", z);
        final ResolvedTileSpecCollection resolvedTiles =
                renderDataClient.getResolvedTiles(clientParameters.inputStack, z);

        for (final TileSpec tileSpec : resolvedTiles.getTileSpecs()) {
            correctTile(clientParameters, tileSpec);
        }

        // Save all modified tile specs to output stack
        renderDataClient.saveResolvedTiles(resolvedTiles, clientParameters.outputStack, z);
        LOG.info("correctZLayer: completed z={}, processed {} tiles", z, resolvedTiles.getTileSpecs().size());
    }

    private void correctTile(final Parameters clientParameters,
                             final TileSpec tileSpec)
            throws IOException {

        final String tileId = tileSpec.getTileId();
        LOG.debug("correctTile: processing tile {}", tileId);

        // Load both channels
        final ImagePlus channel1 = loadChannel(tileSpec, 0);
        final ImagePlus channel2 = loadChannel(tileSpec, 1);

        // Apply streak correction
        final SecondChannelStreakCorrector corrector = clientParameters.streakCorrector.getCorrector();
        final ImagePlus correctedImage = corrector.process(channel1, channel2);

        // Save image to output directory
        final String outputImagePath = saveImage(correctedImage, clientParameters, tileSpec);

        // Update tile spec with new image location
        updateTileSpecWithCorrectedImage(tileSpec, outputImagePath);
        LOG.debug("correctTile: completed tile {}", tileId);
    }

    private ImagePlus loadChannel(final TileSpec tileSpec, final int channel) {
        final String tileUrl = tileSpec.getTileImageUrl();
        if (!tileUrl.endsWith("c0")) {
            throw new IllegalArgumentException("Tile " + tileSpec.getTileId() + " does not have a valid channel URL: " + tileUrl);
        }
        final String channelUrl = tileUrl.replace("c0", "c" + channel);

        final ImageProcessorCache cache = ImageProcessorCache.DISABLED_CACHE;
        final ImageLoader.LoaderType h5Loader = ImageLoader.LoaderType.H5_SLICE;
        final ImageProcessor ip = cache.get(channelUrl, 0, false, false, h5Loader, null);
        return new ImagePlus(tileSpec.getTileId() + "_ch" + channel, ip);
    }

    private String saveImage(final ImagePlus image,
                             final Parameters clientParameters,
                             final TileSpec tileSpec) throws IOException {

        // Get output path based on tile spec and client parameters
        final String outputPath = buildOutputPath(clientParameters, tileSpec);
        FileUtil.ensureWritableDirectory(new File(outputPath).getParentFile());

        // Convert ImagePlus to BufferedImage and mark it as 16bit
        final RenderParameters renderParams = RenderParameters.fromTileSpec(tileSpec);
        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                new TransformMeshMappingWithMasks.ImageProcessorWithMasks(image.getProcessor(), null, null);
        final BufferedImage bufferedImage = ShortRenderer.CONVERTER.convertProcessorWithMasksToImage(renderParams, ipwm);

        Utils.saveImage(bufferedImage, outputPath, "png", false, 1.0f);
        LOG.debug("saveImage: saved image to {}", outputPath);
        return outputPath;
    }

    private String buildOutputPath(final Parameters clientParameters, final TileSpec tileSpec) {
        final int zInt = tileSpec.getZ().intValue();
        final int thousands = zInt / 1000;
        final int hundreds = (zInt % 1000) / 100;

        try {
            final URIBuilder uriBuilder = new URIBuilder(clientParameters.outputDirectory);
			final List<String> pathSegments = uriBuilder.getPathSegments();
            pathSegments.add(clientParameters.renderWeb.project);
            pathSegments.add(clientParameters.outputStack);
            pathSegments.add(clientParameters.runTimestamp);
            pathSegments.add(String.format("%03d", thousands));
            pathSegments.add(String.valueOf(hundreds));
            pathSegments.add(String.valueOf(zInt));
            pathSegments.add(tileSpec.getTileId() + ".png");

            final URI fullUri = uriBuilder.setScheme("file").setPathSegments(pathSegments).build();
            return new File(fullUri).getAbsolutePath();
        } catch (final URISyntaxException e) {
            throw new IllegalArgumentException("Failed to build output path", e);
        }
    }

    private void updateTileSpecWithCorrectedImage(final TileSpec tileSpec, final String imagePath) {
        // Point the tile spec to the new image
		final ChannelSpec channelSpec = tileSpec.getAllChannels().get(0);
        ImageAndMask imageAndMask = channelSpec
                .getFirstMipmapImageAndMask(tileSpec.getTileId())
                .copyWithImage(imagePath, null, null);

        // If the channel has a mask, ensure it is a dynamic mask and update the URL
        if (channelSpec.hasMask()) {
            if (! ImageLoader.LoaderType.DYNAMIC_MASK.equals(imageAndMask.getMaskLoaderType())) {
                throw new IllegalArgumentException("Channel " + channelSpec.getName() +
                                                           " has a mask, but it is not a dynamic mask. Cannot update tile spec.");
            }

            final DynamicMaskLoader.DynamicMaskDescription description = DynamicMaskLoader.parseUrl(imageAndMask.getMaskUrl());
            imageAndMask = imageAndMask.copyWithMask(description.toString(), ImageLoader.LoaderType.DYNAMIC_MASK, null);
        }

        channelSpec.putMipmap(0, imageAndMask);
        LOG.debug("updateTileSpecWithCorrectedImage: updated tile {} with corrected image at {}",
                  tileSpec.getTileId(), imagePath);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StreakCorrectionClient.class);
}
