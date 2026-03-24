package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.CreepCorrectionClient;
import org.janelia.render.client.multisem.CreepCorrectionClient.ZLayerResult;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for applying piezo creep correction to multi-SEM tiles.
 * Each z-layer is processed independently on a Spark executor.
 *
 * <p>Within each z-layer, all mFOVs are processed sequentially: for each mFOV, the y-stretch
 * is estimated from pairwise affine fits of geometrically adjacent sFOV pairs, and a
 * double-exponential correction is applied if validation passes. mFOVs that fail validation
 * are skipped (uploaded without correction).</p>
 *
 * <p>After tile correction, existing point matches are transformed to account for the creep
 * correction and saved to a new match collection (named {@code targetStack + "_match"}).
 * This can be skipped with {@code --skipMatchCorrection}.</p>
 *
 * @see CreepCorrectionClient
 *
 * @author Michael Innerberger
 */
public class CreepCorrectionSparkClient implements Serializable {

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
                names = "--targetStack",
                description = "Name of target stack for corrected tiles",
                required = true)
        public String targetStack;

        @Parameter(
                names = "--matchOwner",
                description = "Owner of match collection (default is same as render owner)")
        public String matchOwner;

        @Parameter(
                names = "--matchCollection",
                description = "Name of match collection containing within-layer montage matches",
                required = true)
        public String matchCollection;

        @Parameter(
                names = "--skipMatchCorrection",
                description = "Skip transforming match coordinates (default is to transform them)")
        public boolean skipMatchCorrection = false;

        String getMatchOwner() {
            return matchOwner != null ? matchOwner : renderWeb.owner;
        }

        String getTargetMatchCollection() {
            return targetStack + "_match";
        }
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final CreepCorrectionSparkClient client = new CreepCorrectionSparkClient(parameters);
                client.run();
            }
        };
        clientRunner.run();
    }

    private final Parameters parameters;

    public CreepCorrectionSparkClient(final Parameters parameters) {
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("CreepCorrectionSparkClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

            final String sparkAppId = sparkContext.getConf().getAppId();
            final String executorsJson = LogUtilities.getExecutorsApiJson(sparkAppId);
            LOG.info("run: appId is {}, executors data is {}", sparkAppId, executorsJson);

            final RenderDataClient sourceDataClient = parameters.renderWeb.getDataClient();

            final List<Double> zValues = sourceDataClient.getStackZValues(parameters.stack,
                                                                          parameters.layerRange.minZ,
                                                                          parameters.layerRange.maxZ);

            if (zValues.isEmpty()) {
                throw new IllegalArgumentException("source stack does not contain any matching z values");
            }

            // set up target stack on the driver
            final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(parameters.stack);
            sourceDataClient.setupDerivedStack(sourceStackMetaData, parameters.targetStack);

            // Phase 1: process tiles and collect corrections
            LOG.info("run: Phase 1 - distributing {} z values for tile correction", zValues.size());

            final JavaRDD<Double> rddZValues = sparkContext.parallelize(zValues);

            final JavaRDD<ZLayerResult> rddResults = rddZValues.map(this::processSingleLayer);
            final List<ZLayerResult> resultList = rddResults.collect();

            // collect all corrections on the driver
            final Map<String, ZLayerResult> allResults = new HashMap<>();
            long totalTiles = 0;
            for (int i = 0; i < zValues.size(); i++) {
                final ZLayerResult result = resultList.get(i);
                totalTiles += result.tileCount;
                allResults.put(String.valueOf(zValues.get(i).doubleValue()), result);
            }

            LOG.info("run: Phase 1 complete - processed {} tiles across {} z-layers", totalTiles, zValues.size());

            // complete target stack on the driver
            sourceDataClient.setStackState(parameters.targetStack, StackMetaData.StackState.COMPLETE);

            // Phase 2: transform matches
            if (!parameters.skipMatchCorrection) {
                transformMatches(sparkContext, allResults);
            } else {
                LOG.info("run: skipping match correction (--skipMatchCorrection)");
            }
        }

        LOG.info("run: exit");
    }

    private void transformMatches(final JavaSparkContext sparkContext,
                                  final Map<String, ZLayerResult> allResults)
            throws IOException {

        final RenderDataClient driverMatchClient = new RenderDataClient(
                parameters.renderWeb.baseDataUrl,
                parameters.getMatchOwner(),
                parameters.matchCollection);

        final List<String> pGroupIds = driverMatchClient.getMatchPGroupIds();

        if (pGroupIds.isEmpty()) {
            LOG.info("transformMatches: no match groups found, skipping");
            return;
        }

        LOG.info("run: Phase 2 - distributing {} match groups for coordinate transformation", pGroupIds.size());

        final Broadcast<Map<String, ZLayerResult>> broadcastResults = sparkContext.broadcast(allResults);

        final JavaRDD<String> rddGroupIds = sparkContext.parallelize(pGroupIds);

        rddGroupIds.foreach(groupId -> {
            transformMatchesForSingleGroup(groupId, broadcastResults.value());
        });

        LOG.info("run: Phase 2 complete - transformed matches for {} groups", pGroupIds.size());
    }

    private ZLayerResult processSingleLayer(final Double z) throws IOException {
        LogUtilities.setupExecutorLog4j("z " + z);

        final RenderDataClient executorRenderClient = parameters.renderWeb.getDataClient();
        final RenderDataClient executorMatchClient = new RenderDataClient(
                parameters.renderWeb.baseDataUrl,
                parameters.getMatchOwner(),
                parameters.matchCollection);

        final CreepCorrectionClient correctionClient = new CreepCorrectionClient();
        return correctionClient.processZLayer(z,
                                              executorRenderClient,
                                              executorMatchClient,
                                              parameters.stack,
                                              parameters.targetStack);
    }

    private void transformMatchesForSingleGroup(final String groupId,
                                                final Map<String, ZLayerResult> allResults)
            throws IOException {
        LogUtilities.setupExecutorLog4j("matchTransform " + groupId);

        final RenderDataClient executorRenderClient = parameters.renderWeb.getDataClient();
        final RenderDataClient sourceMatchClient = new RenderDataClient(
                parameters.renderWeb.baseDataUrl,
                parameters.getMatchOwner(),
                parameters.matchCollection);
        final RenderDataClient targetMatchClient = new RenderDataClient(
                parameters.renderWeb.baseDataUrl,
                parameters.getMatchOwner(),
                parameters.getTargetMatchCollection());

        final CreepCorrectionClient correctionClient = new CreepCorrectionClient();
        correctionClient.transformMatchesForGroup(groupId,
                                                  allResults,
                                                  executorRenderClient,
                                                  sourceMatchClient,
                                                  targetMatchClient,
                                                  parameters.stack);
    }

    private static final Logger LOG = LoggerFactory.getLogger(CreepCorrectionSparkClient.class);
}
