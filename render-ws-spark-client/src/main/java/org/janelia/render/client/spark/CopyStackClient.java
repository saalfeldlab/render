package org.janelia.render.client.spark;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import ij.process.ImageProcessor;

import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.trakem2.transform.AffineModel2D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackMetaData.StackState;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for copying tiles from one stack to another.
 *
 * @author Eric Trautman
 */
public class CopyStackClient implements Serializable {

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
                description = "Name of target stack owner (default is same as source stack owner)"
        )
        private String targetOwner;

        @Parameter(
                names = "--targetProject",
                description = "Name of target stack project (default is same as source stack project)"
        )
        private String targetProject;

        @Parameter(
                names = "--targetStack",
                description = "Name of target stack",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--z",
                description = "Explicit z values for sections to be processed",
                variableArity = true) // e.g. --z 20.0 21.0 22.0
        public List<Double> zValues;

        Set<Double> getZValues() {
            return (zValues == null) ? Collections.emptySet() : new HashSet<>(zValues);
        }

        @Parameter(
                names = "--moveToOrigin",
                description = "If necessary, translate copied stack so that it's minX and minY are near the origin (default is to copy exact location)",
                arity = 0)
        public boolean moveToOrigin = false;

        @Parameter(
                names = "--excludeTileIdsMissingFromStacks",
                description = "Name(s) of stack(s) that contain ids of tiles to be included in target stack (assumes owner and project are same as source stack).",
                variableArity = true
        )
        public List<String> excludeTileIdsMissingFromStacks;

        @Parameter(
                names = "--resetMinAndMaxIntensity",
                description = "Update tile spec min/max intensity values based upon the min/max source intensities for the entire stack",
                arity = 0)
        public boolean resetMinAndMaxIntensity = false;

        @Parameter(
                names = "--completeToStackAfterCopy",
                description = "Complete the to stack after copying all layers",
                arity = 0)
        public boolean completeToStackAfterCopy = false;


        String getTargetOwner() {
            if (targetOwner == null) {
                targetOwner = renderWeb.owner;
            }
            return targetOwner;
        }

        String getTargetProject() {
            if (targetProject == null) {
                targetProject = renderWeb.project;
            }
            return targetProject;
        }

    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final CopyStackClient client = new CopyStackClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    private CopyStackClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run()
            throws IOException {

        final SparkConf conf = new SparkConf().setAppName("CopyStackClient");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final String sparkAppId = sparkContext.getConf().getAppId();
        final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);

        LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);


        final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

        final List<SectionData> sectionDataList =
                sourceDataClient.getStackSectionData(parameters.stack,
                                                     parameters.layerRange.minZ,
                                                     parameters.layerRange.maxZ,
                                                     parameters.getZValues());
        if (sectionDataList.size() == 0) {
            throw new IllegalArgumentException("source stack does not contain any matching z values");
        }

        // batch layers by tile count in attempt to distribute work load as evenly as possible across cores
        final int numberOfCores = sparkContext.defaultParallelism();
        final LayerDistributor layerDistributor = new LayerDistributor(numberOfCores);
        final List<List<Double>> batchedZValues = layerDistributor.distribute(sectionDataList);

        final RenderDataClient targetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                       parameters.getTargetOwner(),
                                                                       parameters.getTargetProject());

        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
        targetDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

        final LeafTransformSpec moveStackTransform;
        if (parameters.moveToOrigin) {
            final StackStats sourceStackStats = sourceStackMetaData.getStats();
            final Bounds sourceStackBounds = sourceStackStats.getStackBounds();

            final double padding = 10.0;
            if ((sourceStackBounds.getMinX() < 0) || (sourceStackBounds.getMinX() > padding) ||
                (sourceStackBounds.getMinY() < 0) || (sourceStackBounds.getMinY() > padding)) {

                final double xOffset = padding - sourceStackBounds.getMinX();
                final double yOffset = padding - sourceStackBounds.getMinY();
                final String dataString = "1 0 0 1 " + xOffset + " " + yOffset;

                final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_hhmmss_SSS");
                moveStackTransform = new LeafTransformSpec("MOVE_STACK_" + sdf.format(new Date()),
                                                           null,
                                                           AffineModel2D.class.getName(),
                                                           dataString);
            } else {
                LOG.info("skipping move to origin since source stack is already near the origin");
                moveStackTransform = null;
            }

        } else {
            moveStackTransform = null;
        }

        final JavaRDD<List<Double>> rddZValues = sparkContext.parallelize(batchedZValues);

        final Map<String, Double[]> channelNameToIntensityRangeForStack = new HashMap<>();

        if (parameters.resetMinAndMaxIntensity) {

            final Function<List<Double>, Map<String, Double[]>> intensityDerivationFunction = (Function<List<Double>, Map<String, Double[]>>) zBatch -> {

                final RenderDataClient localSourceDataClient = parameters.renderWeb.getDataClient();

                final Map<String, Double[]> channelNameToIntensityRangeForBatch = new HashMap<>();

                Double[] channelMinMax;
                for (int i = 0; i < zBatch.size(); i++) {

                    final Double z = zBatch.get(i);

                    LogUtilities.setupExecutorLog4j("z " + z);

                    LOG.info("intensityDerivationFunction: processing layer {} of {}, next layer z values are {}",
                             i + 1, zBatch.size(), zBatch.subList(i + 1, Math.min(zBatch.size(), i + 10)));

                    final ResolvedTileSpecCollection sourceCollection =
                            localSourceDataClient.getResolvedTiles(parameters.stack, z);

                    for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {
                        for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {
                            final ImageAndMask imageAndMask = channelSpec.getFirstMipmapEntry().getValue();
                            final ImageProcessor imageProcessor =
                                    ImageProcessorCache.DISABLED_CACHE.get(imageAndMask.getImageUrl(),
                                                                           0,
                                                                           false,
                                                                           channelSpec.is16Bit());
                            final double minIntensity = imageProcessor.getMin();
                            final double maxIntensity = imageProcessor.getMax();

                            channelMinMax = channelNameToIntensityRangeForBatch.get(channelSpec.getName());
                            if (channelMinMax == null) {
                                channelMinMax = new Double[]{minIntensity, maxIntensity};
                                channelNameToIntensityRangeForBatch.put(channelSpec.getName(), channelMinMax);
                            } else {
                                channelMinMax[0] = Math.min(channelMinMax[0], minIntensity);
                                channelMinMax[1] = Math.max(channelMinMax[1], maxIntensity);
                            }
                        }
                    }

                }

                return channelNameToIntensityRangeForBatch;
            };

            final JavaRDD<Map<String, Double[]>> rddBatchIntensityRanges = rddZValues.map(intensityDerivationFunction);

            final List<Map<String, Double[]>> batchIntensityRangesList = rddBatchIntensityRanges.collect();

            for (final Map<String, Double[]> batchRanges : batchIntensityRangesList) {
                for (final String channelName : batchRanges.keySet()) {
                    final Double[] batchChannelMinMax = batchRanges.get(channelName);
                    final Double[] stackChannelMinMax = channelNameToIntensityRangeForStack.get(channelName);
                    if (stackChannelMinMax == null) {
                        channelNameToIntensityRangeForStack.put(channelName, batchChannelMinMax);
                    } else {
                        stackChannelMinMax[0] = Math.min(stackChannelMinMax[0], batchChannelMinMax[0]);
                        stackChannelMinMax[1] = Math.max(stackChannelMinMax[1], batchChannelMinMax[1]);
                    }
                }
            }

            LOG.info("run: collected batch intensity ranges");
            
            for (final String channelName : channelNameToIntensityRangeForStack.keySet()) {
                final Double[] stackChannelMinMax = channelNameToIntensityRangeForStack.get(channelName);
                LOG.info("run: minIntensity is {} and maxIntensity is {} for stack channel {}",
                         stackChannelMinMax[0], stackChannelMinMax[1], channelName);
            }

       }

        final Function<List<Double>, Long> copyFunction = (Function<List<Double>, Long>) zBatch -> {

            final RenderDataClient localSourceDataClient = parameters.renderWeb.getDataClient();

            final RenderDataClient localTargetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                                parameters.getTargetOwner(),
                                                                                parameters.getTargetProject());

            long processedTileCount = 0;

            for (int i = 0; i < zBatch.size(); i++) {

                final Double z = zBatch.get(i);

                LogUtilities.setupExecutorLog4j("z " + z);

                LOG.info("copyFunction: processing layer {} of {}, next layer z values are {}",
                         i + 1, zBatch.size(), zBatch.subList(i+1, Math.min(zBatch.size(), i+10)));

                final ResolvedTileSpecCollection sourceCollection =
                        localSourceDataClient.getResolvedTiles(parameters.stack, z);

                final Set<String> tileIdsToKeep = new HashSet<>();
                String filterStack = null;
                if (parameters.excludeTileIdsMissingFromStacks != null) {

                    for (final String tileIdStack : parameters.excludeTileIdsMissingFromStacks) {

                        tileIdsToKeep.addAll(
                                localSourceDataClient.getTileBounds(tileIdStack, z)
                                        .stream()
                                        .map(TileBounds::getTileId)
                                        .collect(Collectors.toList()));

                        // once a stack with tiles for the current z is found, use that as the filter
                        if (tileIdsToKeep.size() > 0) {
                            filterStack = tileIdStack;
                            break;
                        }
                    }

                }

                if (tileIdsToKeep.size() > 0) {
                    final int numberOfTilesBeforeFilter = sourceCollection.getTileCount();
                    sourceCollection.removeDifferentTileSpecs(tileIdsToKeep);
                    final int numberOfTilesRemoved = numberOfTilesBeforeFilter - sourceCollection.getTileCount();
                    LOG.info("copyFunction: removed {} tiles not found in {}", numberOfTilesRemoved, filterStack);
                }

                if (moveStackTransform != null) {
                    sourceCollection.addTransformSpecToCollection(moveStackTransform);
                    sourceCollection.addReferenceTransformToAllTiles(moveStackTransform.getId(), false);
                }

                sourceCollection.removeUnreferencedTransforms();

                if (parameters.resetMinAndMaxIntensity) {
                    for (final String channelName : channelNameToIntensityRangeForStack.keySet()) {
                        final Double[] stackChannelMinMax = channelNameToIntensityRangeForStack.get(channelName);
                        for (final TileSpec tileSpec : sourceCollection.getTileSpecs()) {
                            tileSpec.setMinAndMaxIntensity(stackChannelMinMax[0], stackChannelMinMax[1], channelName);
                        }
                    }
                }

                localTargetDataClient.saveResolvedTiles(sourceCollection, parameters.targetStack, z);

                processedTileCount += sourceCollection.getTileCount();
            }

            return processedTileCount;
        };

        final JavaRDD<Long> rddTileCounts = rddZValues.map(copyFunction);

        final List<Long> tileCountList = rddTileCounts.collect();
        long total = 0;
        for (final Long tileCount : tileCountList) {
            total += tileCount;
        }

        LOG.info("run: collected stats");
        LOG.info("run: copied {} tiles", total);

        if (parameters.completeToStackAfterCopy) {
            final RenderDataClient driverTargetDataClient = new RenderDataClient(parameters.renderWeb.baseDataUrl,
                                                                                 parameters.getTargetOwner(),
                                                                                 parameters.getTargetProject());
            driverTargetDataClient.setStackState(parameters.targetStack, StackState.COMPLETE);
        }

        sparkContext.stop();
    }

    private static final Logger LOG = LoggerFactory.getLogger(CopyStackClient.class);
}
