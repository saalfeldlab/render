package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.multisem.CreepCorrectionClient;
import org.janelia.render.client.multisem.CreepCorrectionClient.MfovResult;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.CreepCorrectionParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
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
public class CreepCorrectionSparkClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject;

        @ParametersDelegate
        public CreepCorrectionParameters creepCorrection;
    }

    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                parameters.creepCorrection.validate();

                LOG.info("runClient: entry, parameters={}", parameters);

                final CreepCorrectionSparkClient client = new CreepCorrectionSparkClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    public CreepCorrectionSparkClient() {
    }

    /**
     * Create a spark context and run the client with the specified parameters.
     */
    public void createContextAndRun(final Parameters clientParameters)
            throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());

            for (final StackWithZValues stackWithAllZ : clientParameters.multiProject.buildListOfStackWithAllZ()) {
                correctCreep(sparkContext,
                             clientParameters.multiProject.getBaseDataUrl(),
                             stackWithAllZ,
                             clientParameters.creepCorrection,
                             clientParameters.multiProject.getMatchCollectionIdForStack(stackWithAllZ.getStackId()));
            }
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        final CreepCorrectionParameters creepCorrection = pipelineParameters.getCreepCorrection();
        AlignmentPipelineParameters.validateRequiredElementExists("creepCorrection",
                                                                  creepCorrection);
        creepCorrection.validate();
    }

    /** Run the client as part of an alignment pipeline. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {
        
        final MultiProjectParameters multiProject =
                pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());

        for (final StackWithZValues stackWithAllZ : multiProject.buildListOfStackWithAllZ()) {
            correctCreep(sparkContext,
                         multiProject.getBaseDataUrl(),
                         stackWithAllZ,
                         pipelineParameters.getCreepCorrection(),
                         multiProject.getMatchCollectionIdForStack(stackWithAllZ.getStackId()));
        }
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.CORRECT_CREEP;
    }

    public void correctCreep(final JavaSparkContext sparkContext,
                             final String baseDataUrl,
                             final StackWithZValues stackWithAllZ,
                             final CreepCorrectionParameters creepCorrection,
                             final MatchCollectionId matchCollectionId) throws IOException {

        final StackId sourceStackId = stackWithAllZ.getStackId();
        final String sourceStackDevString = sourceStackId.toDevString();

        LOG.info("correctCreep: entry, {} with z {} to {} and creepCorrection {}",
                 sourceStackDevString, stackWithAllZ.getFirstZ(), stackWithAllZ.getLastZ(), creepCorrection);

        final String sourceStack = sourceStackId.getStack();
        final String targetStack = creepCorrection.getTargetStack(sourceStack);
        final MatchCollectionId targetMatchCollectionId = new MatchCollectionId(matchCollectionId.getOwner(),
                                                                                targetStack + "_match");

        final RenderDataClient sourceDataClient = new RenderDataClient(baseDataUrl,
                                                                       sourceStackId.getOwner(),
                                                                       sourceStackId.getProject());

        final List<Double> correctedZValues = creepCorrection.buildCorrectedZValues(stackWithAllZ.getzValues());
        if (correctedZValues.isEmpty()) {
            throw new IllegalArgumentException("source stack does not contain any matching z values to be corrected");
        }

        // set up target stack on the driver
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(sourceStack);
        sourceDataClient.setupDerivedStack(sourceStackMetaData, targetStack);

        // Phase 1: process tiles and collect corrections
        LOG.info("correctCreep: {}, phase 1 - distributing {} z values for tile correction",
                 sourceStackDevString, correctedZValues.size());

        final JavaRDD<Double> rddCorrectedZValues = sparkContext.parallelize(correctedZValues);

        final JavaRDD<List<MfovResult>> rddResults =
                rddCorrectedZValues.map(z -> processSingleLayer(
                        baseDataUrl,
                        sourceStackId,
                        creepCorrection.targetStackSuffix,
                        z,
                        matchCollectionId));
        final List<List<MfovResult>> resultList = rddResults.collect();

        // collect all corrections on the driver
        final Map<String, List<MfovResult>> allResults = new HashMap<>();
        for (int i = 0; i < correctedZValues.size(); i++) {
            allResults.put(String.valueOf(correctedZValues.get(i).doubleValue()), resultList.get(i));
        }

        final List<Double> uncorrectedZValues = creepCorrection.buildUncorrectedZValues(stackWithAllZ.getzValues());
        if (! uncorrectedZValues.isEmpty()) {

            LOG.info("correctCreep: {}, phase 1 - distributing {} z values for simple copy",
                     sourceStackDevString, uncorrectedZValues.size());

            final JavaRDD<Double> rddUncorrectedZValues = sparkContext.parallelize(uncorrectedZValues);
            final JavaRDD<Double> rddCopyResults =
                    rddUncorrectedZValues.map(z -> copySingleLayer(
                            baseDataUrl,
                            sourceStackId,
                            creepCorrection.targetStackSuffix,
                            z));
            rddCopyResults.collect();
        }

        LOG.info("correctCreep: {}, phase 1 complete - processed {} z-layers",
                 sourceStackDevString, correctedZValues.size());

        // write or log results on the driver
        reportResults(creepCorrection, sourceStackId, allResults);

       // complete target stack on the driver
        sourceDataClient.setStackState(targetStack, StackMetaData.StackState.COMPLETE);

        // Phase 2: transform matches
        if (! creepCorrection.skipMatchCorrection) {
            transformMatches(sparkContext, baseDataUrl, matchCollectionId, targetMatchCollectionId, allResults);
        } else {
            LOG.info("correctCreep: skipping match correction");
        }

        LOG.info("correctCreep: exit");
    }

    private void transformMatches(final JavaSparkContext sparkContext,
                                  final String baseDataUrl,
                                  final MatchCollectionId matchCollectionId,
                                  final MatchCollectionId targetMatchCollectionId,
                                  final Map<String, List<MfovResult>> allResults)
            throws IOException {

        final RenderDataClient driverMatchClient = new RenderDataClient(baseDataUrl,
                                                                        matchCollectionId.getOwner(),
                                                                        matchCollectionId.getName());

        final List<String> pGroupIds = driverMatchClient.getMatchPGroupIds();

        if (pGroupIds.isEmpty()) {
            LOG.info("transformMatches: no match groups found, skipping");
            return;
        }

        LOG.info("transformMatches: Phase 2 - distributing {} match groups for coordinate transformation",
                 pGroupIds.size());

        final Broadcast<Map<String, List<MfovResult>>> broadcastResults = sparkContext.broadcast(allResults);

        final JavaRDD<String> rddGroupIds = sparkContext.parallelize(pGroupIds);

        rddGroupIds.foreach(groupId ->
                                    transformMatchesForSingleGroup(baseDataUrl,
                                                                   matchCollectionId,
                                                                   targetMatchCollectionId,
                                                                   groupId,
                                                                   broadcastResults.value()));

        LOG.info("transformMatches: Phase 2 complete - transformed matches for {} groups",
                 pGroupIds.size());
    }

    private List<MfovResult> processSingleLayer(final String baseDataUrl,
                                                final StackId stackId,
                                                final String targetStackSuffix,
                                                final Double z,
                                                final MatchCollectionId matchCollectionId) throws IOException {

        LogUtilities.setupExecutorLog4j(stackId.toDevString() + "::z" + z);

        final RenderDataClient executorRenderClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());
        final RenderDataClient executorMatchClient = new RenderDataClient(baseDataUrl,
                                                                          matchCollectionId.getOwner(),
                                                                          matchCollectionId.getName());

        final CreepCorrectionClient correctionClient = new CreepCorrectionClient();
        final String targetStack = stackId.getStack() + targetStackSuffix;
        return correctionClient.processZLayer(z,
                                              executorRenderClient,
                                              executorMatchClient,
                                              stackId.getStack(),
                                              targetStack);
    }

    private Double copySingleLayer(final String baseDataUrl,
                                   final StackId stackId,
                                   final String targetStackSuffix,
                                   final Double z) throws IOException {

        LogUtilities.setupExecutorLog4j(stackId.toDevString() + "::z" + z);

        final RenderDataClient executorRenderClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());
        final String targetStack = stackId.getStack() + targetStackSuffix;

        final ResolvedTileSpecCollection resolvedTiles = executorRenderClient.getResolvedTiles(stackId.getStack(), z);
        executorRenderClient.saveResolvedTiles(resolvedTiles, targetStack, z);

        return z;
    }

    private void transformMatchesForSingleGroup(final String baseDataUrl,
                                                final MatchCollectionId matchCollectionId,
                                                final MatchCollectionId targetMatchCollectionId,
                                                final String groupId,
                                                final Map<String, List<MfovResult>> allResults)
            throws IOException {

        LogUtilities.setupExecutorLog4j(matchCollectionId.toDevString() + "::" + groupId);

        final RenderDataClient sourceMatchClient = new RenderDataClient(baseDataUrl,
                                                                        matchCollectionId.getOwner(),
                                                                        matchCollectionId.getName());
        final RenderDataClient targetMatchClient = new RenderDataClient(baseDataUrl,
                                                                        targetMatchCollectionId.getOwner(),
                                                                        targetMatchCollectionId.getName());

        final CreepCorrectionClient correctionClient = new CreepCorrectionClient();
        correctionClient.transformMatchesForGroup(groupId,
                                                  allResults,
                                                  sourceMatchClient,
                                                  targetMatchClient);
    }

    private void reportResults(final CreepCorrectionParameters creepCorrection,
                               final StackId sourceStackId,
                               final Map<String, List<MfovResult>> allResults) {

        final List<String> sortedScans = new ArrayList<>(allResults.keySet());
        sortedScans.sort(Comparator.comparingDouble(Double::parseDouble));

        boolean written = false;
        if (creepCorrection.parameterCsvDir != null) {

            final File csvFile = new File(creepCorrection.parameterCsvDir,
                                          "creep-correction-param." + sourceStackId.getStack() + ".csv");

            try (final PrintWriter writer = new PrintWriter(csvFile)) {
                writer.println("scan," + MfovResult.CSV_HEADER);
                for (final String scan : sortedScans) {
                    for (final MfovResult result : allResults.get(scan)) {
                        writer.println(scan + "," + result.toCsvRow());
                    }
                }
                written = true;
                LOG.info("reportResults: wrote creep correction parameters to {}", csvFile);
            } catch (final Exception e) {
                LOG.error("reportResults: failed to write CSV to {}, logging instead", csvFile, e);
            }
        }

        if (!written) {
            LOG.info("reportResults: scan,{}", MfovResult.CSV_HEADER);
            for (final String scan : sortedScans) {
                for (final MfovResult result : allResults.get(scan)) {
                    LOG.info("reportResults: {},{}", scan, result.toCsvRow());
                }
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(CreepCorrectionSparkClient.class);
}
