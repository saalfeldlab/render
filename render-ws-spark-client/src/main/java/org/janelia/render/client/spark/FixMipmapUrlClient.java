package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.RenderDataClientParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for changing the imageUrl and/or maskUrl attributes for all tile specs in a stack.
 *
 * @author Eric Trautman
 */
public class FixMipmapUrlClient
        implements Serializable {

    private enum UrlType { IMAGE, MASK, BOTH }

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
                description = "Minimum Z value for sections to be fixed",
                required = false)
        private Double minZ;

        @Parameter(
                names = "--maxZ",
                description = "Maximum Z value for sections to be fixed",
                required = false)
        private Double maxZ;

        @Parameter(
                names = "--replacePattern",
                description = "Source pattern identifying what needs to be replaced",
                required = true)
        private String replacePattern;

        @Parameter(
                names = "--replaceWith",
                description = "Replacement value",
                required = true)
        private String replaceWith;

        @Parameter(
                names = "--urlType",
                description = "Identifies which URLs to fix",
                required = false)
        private final UrlType urlType = UrlType.BOTH;

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

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args, FixMipmapUrlClient.class);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FixMipmapUrlClient client = new FixMipmapUrlClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public FixMipmapUrlClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("FixMipmapUrlClient");
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

        final StackMetaData targetStackMetaData = targetDataClient.getStackMetaData(parameters.getTargetStack());
        if (! targetStackMetaData.isLoading()) {
            throw new IllegalArgumentException("target stack must be in the loading state, meta data is " +
                                               targetStackMetaData);
        }

        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> transformFunction = new Function<Double, Integer>() {

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

                final Pattern pattern = Pattern.compile(parameters.replacePattern);
                final boolean fixImage = UrlType.BOTH.equals(parameters.urlType) ||
                                         UrlType.IMAGE.equals(parameters.urlType);
                final boolean fixMask = UrlType.BOTH.equals(parameters.urlType) ||
                                        UrlType.MASK.equals(parameters.urlType);

                ImageAndMask imageAndMask;
                ImageAndMask fixedImageAndMask;
                String imageUrl;
                String maskUrl;
                for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {
                    final Map.Entry<Integer, ImageAndMask> maxEntry = tileSpec.getFloorMipmapEntry(Integer.MAX_VALUE);
                    if (maxEntry != null) {
                        for (int level = maxEntry.getKey(); level >= 0; level--) {
                            imageAndMask = tileSpec.getMipmap(level);
                            if (imageAndMask != null) {

                                if (fixImage) {
                                    imageUrl = fixUrl(pattern, imageAndMask.getImageUrl(), parameters.replaceWith);
                                } else {
                                    imageUrl = imageAndMask.getImageUrl();
                                }

                                if (fixMask) {
                                    maskUrl = fixUrl(pattern, imageAndMask.getMaskUrl(), parameters.replaceWith);
                                } else {
                                    maskUrl = imageAndMask.getMaskUrl();
                                }

                                fixedImageAndMask = new ImageAndMask(imageUrl, maskUrl);
                                fixedImageAndMask.validate();
                                tileSpec.putMipmap(level, fixedImageAndMask);
                            }

                        }
                    }

                }

                targetDataClient.saveResolvedTiles(sourceCollection, parameters.getTargetStack(), z);

                return sourceCollection.getTileCount();
            }
        };

        // assign a transformation to the RDD
        final JavaRDD<Integer> rddTileCounts = rddZValues.map(transformFunction);

        // use an action to get the results
        final List<Integer> tileCountList = rddTileCounts.collect();
        long total = 0;

        for (final Integer tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: saved {} tiles and transforms", total);

        sparkContext.stop();
    }

    public static String fixUrl(final Pattern pattern,
                                final String sourceUrl,
                                final String replacement) {
        final Matcher m = pattern.matcher(sourceUrl);
        return m.replaceAll(replacement);
    }

    private static final Logger LOG = LoggerFactory.getLogger(FixMipmapUrlClient.class);
}
