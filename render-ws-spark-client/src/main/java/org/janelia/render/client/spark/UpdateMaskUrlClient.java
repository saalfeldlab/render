package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for adding or removing maskUrl attributes to all tile specs in a stack.
 * When adding masks, tile size is used to determine which mask (from a list of masks) is should be used for each tile.
 *
 * @author Eric Trautman
 */
public class UpdateMaskUrlClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public RenderWebServiceParameters renderWeb = new RenderWebServiceParameters();

        @ParametersDelegate
        public ZRangeParameters layerRange = new ZRangeParameters();

        @Parameter(
                names = "--stack",
                description = "Name of source stack",
                required = true)
        public String stack;

        @Parameter(
                names = "--targetOwner",
                description = "Name of target stack owner (default is same as source stack owner)",
                required = false)
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)",
                required = false)
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = false)
        public String targetStack;

        @Parameter(
                names = "--maskListFile",
                description = "File containing paths of different sized mask files",
                required = false)
        public String maskListFile;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after adding masks",
                required = false, arity = 0)
        public boolean completeTargetStack = false;

        @Parameter(
                names = "--removeMasks",
                description = "Remove all masks (instead of adding missing masks)",
                required = false, arity = 0)
        public boolean removeMasks = false;

        public String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = renderWeb.owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = renderWeb.project;
            }
            return targetProject;
        }

        public String getTargetStack() {
            if ((targetStack == null) || (targetStack.trim().length() == 0)) {
                targetStack = stack;
            }
            return targetStack;
        }

        public void validateMaskListFile() {

            if (! removeMasks) {
                if ((maskListFile == null) || ! new File(maskListFile).exists()) {
                    throw new IllegalArgumentException("--maskListFile parameter must identify existing file when adding masks");
                }
            }

        }
    }

    private static class MaskData implements Serializable {

        public String path;
        public int width;
        public int height;

        public MaskData(final String path,
                        final int width,
                        final int height) {
            this.path = path;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return "{\"width\": " + width + ", \"height\": " + height + ", \"path\": \"" + path + "\"}";
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.validateMaskListFile();

                LOG.info("runClient: entry, parameters={}", parameters);

                final UpdateMaskUrlClient client = new UpdateMaskUrlClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public UpdateMaskUrlClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    private static List<MaskData> loadMaskData(final String maskListFileName)
            throws IOException {

        final List<MaskData> maskDataList = new ArrayList<>();

        for (final String line : java.nio.file.Files.readAllLines(Paths.get(maskListFileName),
                                                                  Charset.defaultCharset())) {
            final String path = line.trim();
            if (path.length() > 0) {
                final BufferedImage mask = Utils.openImage(path);
                maskDataList.add(new MaskData(path, mask.getWidth(), mask.getHeight()));
            }
        }

        return maskDataList;
    }

    public void run()
            throws IOException, URISyntaxException, InterruptedException {

        final SparkConf conf = new SparkConf().setAppName("UpdateMaskUrlClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<SectionData> sectionDataList =
                sourceDataClient.getStackSectionData(parameters.stack,
                                                     parameters.layerRange.minZ,
                                                     parameters.layerRange.maxZ);
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());

        // Batch layers by tile count in attempt to distribute work load as evenly as possible across cores.

        // For stacks with many thousands of single (or few) tile layers,
        // this also prevents TCP TIME_WAIT issues on the server by reusing the same HTTP client for many layers.

        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final JavaRDD<List<Double>> rddZValues = sparkContext.parallelize(batchedZValues);

        final JavaRDD<Integer> rddTileCounts;

        List<MaskData> maskDataList = null;
        if (! parameters.removeMasks) {
            maskDataList = loadMaskData(parameters.maskListFile);
            LOG.info("loaded {} from {}", maskDataList, parameters.maskListFile);
        }

        rddTileCounts = rddZValues.map(getUpdateMaskFunction(maskDataList));

        // use an action to get the results
        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;

        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: saved {} tiles and transforms", total);

        sparkContext.stop();

        if (parameters.completeTargetStack) {
            targetDataClient.setStackState(parameters.getTargetStack(), StackMetaData.StackState.COMPLETE);
        }
    }

    public static String getMaskUrl(final ImageAndMask imageAndMask,
                                    final List<MaskData> maskDataList) {
        final BufferedImage image = Utils.openImage(imageAndMask.getImageFilePath());
        final int width = image.getWidth();
        final int height = image.getHeight();
        for (final MaskData maskData : maskDataList) {
            if ((maskData.width == width) && (maskData.height == height)) {
                return "file:" + maskData.path;
            }
        }
        throw new IllegalArgumentException("no mask found for (" + width + "x" + height + ") image " +
                                           imageAndMask.getImageFilePath());
    }

    private Function<List<Double>, Integer> getUpdateMaskFunction(final List<MaskData> maskDataList) {

        return (Function<List<Double>, Integer>) zBatch -> {

            int tileCount = 0;

            // use same source and target clients for each zBatch to prevent TCP TIME_WAIT issues on the server
            final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

            final RenderDataClient targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                           parameters.getTargetOwner(),
                                                                           parameters.getTargetProject());
            for (final Double z : zBatch) {

                LogUtilities.setupExecutorLog4j("z " + z);

                final ResolvedTileSpecCollection sourceCollection =
                        sourceDataClient.getResolvedTiles(parameters.stack, z);

                boolean updatedAtLeastOneSpec = false;

                ImageAndMask imageAndMask;
                ImageAndMask fixedImageAndMask;
                String maskUrl;
                for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {
                    final Map.Entry<Integer, ImageAndMask> firstMipmapEntry = tileSpec.getFirstMipmapEntry();
                    if (firstMipmapEntry != null) {
                        imageAndMask = firstMipmapEntry.getValue();
                        if (imageAndMask != null) {

                            if (parameters.removeMasks) {

                                if (imageAndMask.getMaskUrl() != null) {
                                    fixedImageAndMask = new ImageAndMask(imageAndMask.getImageUrl(), null);
                                    for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {
                                        channelSpec.putMipmap(firstMipmapEntry.getKey(), fixedImageAndMask);
                                    }
                                    updatedAtLeastOneSpec = true;
                                }

                            } else {

                                maskUrl = getMaskUrl(imageAndMask, maskDataList);
                                fixedImageAndMask = new ImageAndMask(imageAndMask.getImageUrl(), maskUrl);
                                fixedImageAndMask.validate();
                                for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {
                                    channelSpec.putMipmap(firstMipmapEntry.getKey(), fixedImageAndMask);
                                }
                                updatedAtLeastOneSpec = true;

                            }
                        }
                    }
                }

                if (updatedAtLeastOneSpec) {
                    targetDataClient.saveResolvedTiles(sourceCollection, parameters.getTargetStack(), z);
                    tileCount += sourceCollection.getTileCount();
                } else {
                    LOG.info("no changes necessary for z {}", z);
                }
            }

            return tileCount;
        };

    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdateMaskUrlClient.class);
}
