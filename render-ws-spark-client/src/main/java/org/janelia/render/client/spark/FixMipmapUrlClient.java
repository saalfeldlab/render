package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
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
 * Spark client for changing the imageUrl and/or maskUrl attributes for all tile specs in a stack.
 *
 * @author Eric Trautman
 */
public class FixMipmapUrlClient
        implements Serializable {

    public enum UrlType { IMAGE, MASK, BOTH }

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
                description = "Name of target stack owner (default is same as source stack owner)")
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)")
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack")
        private String targetStack;

        @Parameter(
                names = "--replacementDataFile",
                description = "File containing tab separated patterns and replacement values",
                required = true)
        public String replacementDataFile;

        @Parameter(
                names = "--urlType",
                description = "Identifies which URLs to fix")
        public UrlType urlType = UrlType.BOTH;

        @Parameter(
                names = "--completeStackAfterFix",
                description = "Complete the target stack after fixing all layers",
                arity = 0)
        public boolean completeStackAfterFix = false;

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

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

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

    private static LinkedHashMap<Pattern, String> loadReplacementData(final String replacementDataFileName)
            throws IOException {

        // order is important, use linked map
        final LinkedHashMap<Pattern, String> replacementData = new LinkedHashMap<>();

        final Pattern pattern = Pattern.compile("[^\\t]++");
        for (final String line : java.nio.file.Files.readAllLines(Paths.get(replacementDataFileName),
                                                                  Charset.defaultCharset())) {
            final Matcher m = pattern.matcher(line);
            if (m.find()) {
                final String replacementPatternString = line.substring(m.start(), m.end());
                if (m.find()) {
                    final String replaceWith = line.substring(m.start(), m.end());
                    replacementData.put(Pattern.compile(replacementPatternString), replaceWith);
                }
            }
        }

        return replacementData;
    }

    public void run()
            throws IOException, URISyntaxException {

        final SparkConf conf = new SparkConf().setAppName("FixMipmapUrlClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                      parameters.layerRange.minZ,
                                                                      parameters.layerRange.maxZ);

        if (zValues.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.getTargetStack());

        final LinkedHashMap<Pattern, String> replacementData = loadReplacementData(parameters.replacementDataFile);

        final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

        final Function<Double, Integer> transformFunction = z -> {

            LogUtilities.setupExecutorLog4j("z " + z);
            //get the source client
            final RenderDataClient sourceDataClient1 = parameters.renderWeb.getDataClient();

            //get the target client(which can be the same as the source)
            final RenderDataClient targetDataClient1 = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                            parameters.getTargetOwner(),
                                                                            parameters.getTargetProject());

            final ResolvedTileSpecCollection sourceCollection =
                    sourceDataClient1.getResolvedTiles(parameters.stack, z);

            final boolean fixImage = UrlType.BOTH.equals(parameters.urlType) ||
                                     UrlType.IMAGE.equals(parameters.urlType);
            final boolean fixMask = UrlType.BOTH.equals(parameters.urlType) ||
                                    UrlType.MASK.equals(parameters.urlType);

            boolean fixedAtLeastOneSpec = false;
            ImageAndMask imageAndMask;
            ImageAndMask fixedImageAndMask;
            String imageUrl;
            String maskUrl;
            for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {

                for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

                    final Map.Entry<Integer, ImageAndMask> maxEntry = channelSpec.getFloorMipmapEntry(Integer.MAX_VALUE);
                    if (maxEntry != null) {
                        for (int level = maxEntry.getKey(); level >= 0; level--) {
                            imageAndMask = channelSpec.getMipmap(level);
                            if (imageAndMask != null) {

                            if (fixImage) {
                                imageUrl = imageAndMask.getImageUrl();
                                if ((imageUrl != null) && (imageUrl.length() > 0)) {
                                    for (final Pattern p : replacementData.keySet()) {
                                        imageUrl = fixUrl(p, imageUrl, replacementData.get(p));
                                    }
                                }
                            } else {
                                imageUrl = imageAndMask.getImageUrl();
                            }

                            if (fixMask) {
                                maskUrl = imageAndMask.getMaskUrl();
                                if ((maskUrl != null) && (maskUrl.length() > 0)) {
                                    for (final Pattern p : replacementData.keySet()) {
                                        maskUrl = fixUrl(p, maskUrl, replacementData.get(p));
                                    }
                                }
                            } else {
                                maskUrl = imageAndMask.getMaskUrl();
                            }

                                fixedImageAndMask = imageAndMask.copyWithDerivedUrls(imageUrl, maskUrl);
                                fixedImageAndMask.validate();

                            final boolean imagePathChanged = fixImage && (imageUrl != null) &&
                                                             (! imageUrl.equals(imageAndMask.getImageUrl()));
                            final boolean maskPathChanged = fixMask && (maskUrl != null) &&
                                                            (! maskUrl.equals(imageAndMask.getMaskUrl()));
                            if (imagePathChanged || maskPathChanged) {
                                fixedAtLeastOneSpec = true;
                                channelSpec.putMipmap(level, fixedImageAndMask);
                            }
                        }

                        }
                    }
                }

            }

            if (fixedAtLeastOneSpec) {
                targetDataClient1.saveResolvedTiles(sourceCollection, parameters.getTargetStack(), z);
            } else {
                LOG.info("no changes necessary for z {}", z);
            }

            return sourceCollection.getTileCount();
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

        if (parameters.completeStackAfterFix) {
            targetDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);
        }

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
