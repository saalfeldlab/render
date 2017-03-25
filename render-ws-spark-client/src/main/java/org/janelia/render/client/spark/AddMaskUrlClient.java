package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.awt.image.BufferedImage;
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
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for adding maskUrl attributes to all tile specs in a stack using the tile size to
 * determine which mask (from a list of masks) is should be used for each tile.
 *
 * @author Eric Trautman
 */
public class AddMaskUrlClient
        implements Serializable {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(
                names = "--stack",
                description = "Name of source stack",
                required = true)
        private String stack;

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
        private String targetStack;

        @Parameter(
                names = "--minZ",
                description = "Minimum Z value for sections to be changed",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be changed",
                required = false)
        private Double maxZ;

        @Parameter(
                names = "--maskListFile",
                description = "File containing paths of different sized mask files",
                required = true)
        private String maskListFile;

        @Parameter(
                names = "--completeTargetStack",
                description = "Complete the target stack after adding masks",
                required = false, arity = 0)
        private boolean completeTargetStack = false;

        public String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = owner;
            }
            return targetOwner;
        }

        public String getTargetProject() {
            if (targetProject == null) {
                targetProject = project;
            }
            return targetProject;
        }

        public String getTargetStack() {
            if ((targetStack == null) || (targetStack.trim().length() == 0)) {
                targetStack = stack;
            }
            return targetStack;
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
                parameters.parse(args, AddMaskUrlClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final AddMaskUrlClient client = new AddMaskUrlClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public AddMaskUrlClient(final Parameters parameters) {
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
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("AddMaskUrlClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.owner,
                                                                       parameters.project);

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.minZ,
                                                                      parameters.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());

        final List<MaskData> maskDataList = loadMaskData(parameters.maskListFile);
        LOG.info("loaded {} from {}", maskDataList, parameters.maskListFile);

        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> addMaskFunction = new Function<Double, Integer>() {

            final
            @Override
            public Integer call(final Double z)
                    throws Exception {

                LogUtilities.setupExecutorLog4j("z " + z);
                //get the source client
                final RenderDataClient sourceDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.owner,
                                                                               parameters.project);
                //get the target client(which can be the same as the source)
                final RenderDataClient targetDataClient = new RenderDataClient(parameters.baseDataUrl,
                                                                               parameters.getTargetOwner(),
                                                                               parameters.getTargetProject());

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
                if (updatedAtLeastOneSpec) {
                    targetDataClient.saveResolvedTiles(sourceCollection, parameters.getTargetStack(), z);
                } else {
                    LOG.info("no changes necessary for z {}", z);
                }

                return sourceCollection.getTileCount();
            }
        };

        final JavaRDD<Integer> rddTileCounts = rddZValues.map(addMaskFunction);

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

    private static final Logger LOG = LoggerFactory.getLogger(AddMaskUrlClient.class);
}
