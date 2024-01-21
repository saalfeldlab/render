package org.janelia.render.client.spark.zspacing;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.ZRangeParameters;
import org.janelia.render.client.parameter.ZSpacingParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.zspacing.CrossCorrelationData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for generating z-layer cross correlation data and then using that data
 * to estimate thickness for a range of z-layers in an aligned render stack.
 *
 * @author Eric Trautman
 */
public class ZPositionCorrectionClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public ZSpacingParameters zSpacing = new ZSpacingParameters();

        public org.janelia.render.client.zspacing.ZPositionCorrectionClient buildJavaClient(final StackWithZValues stackIdWithZValues,
                                                                                            final int comparisonRange,
                                                                                            final String runName,
                                                                                            final boolean solveExisting)
                throws IOException {

            // TODO: think about alternative naming/packaging for related java and spark clients that would avoid need for long ugly declarations like this
            final org.janelia.render.client.zspacing.ZPositionCorrectionClient.Parameters jClientParameters =
                    new org.janelia.render.client.zspacing.ZPositionCorrectionClient.Parameters();

            jClientParameters.renderWeb.baseDataUrl = multiProject.baseDataUrl;

            final StackId stackId = stackIdWithZValues.getStackId();
            jClientParameters.renderWeb.owner = stackId.getOwner();
            jClientParameters.renderWeb.project = stackId.getProject();
            jClientParameters.stack = stackId.getStack();

            jClientParameters.layerRange = new ZRangeParameters(stackIdWithZValues.getFirstZ(),
                                                                stackIdWithZValues.getLastZ());

            if (! solveExisting) {
                // if deriving cross correlation for a batch of z layers (and not solving),
                // make sure each batch overlaps enough (comparisonRange) with its prior batch
                // so that nothing is missed at the batch boundaries
                jClientParameters.layerRange.minZ -= comparisonRange;
            }

            jClientParameters.zSpacing = zSpacing;
            jClientParameters.zSpacing.runName = runName;
            jClientParameters.zSpacing.solveExisting = solveExisting;

            return new org.janelia.render.client.zspacing.ZPositionCorrectionClient(jClientParameters);
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final ZPositionCorrectionClient client = new ZPositionCorrectionClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public ZPositionCorrectionClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
            deriveAndSolveCrossCorrelationData(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("zSpacing",
                                                                  pipelineParameters.getZSpacing());
    }

    /** Run the client as part of an alignment pipeline. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final Parameters clientParameters = new Parameters();
        clientParameters.multiProject = pipelineParameters.getMultiProject(pipelineParameters.getAlignedNamingGroup());
        clientParameters.zSpacing = pipelineParameters.getZSpacing();
        deriveAndSolveCrossCorrelationData(sparkContext, clientParameters);
    }

    private void deriveAndSolveCrossCorrelationData(final JavaSparkContext sparkContext,
                                                    final Parameters clientParameters)
            throws IOException {

        final int comparisonRange = clientParameters.zSpacing.getComparisonRange();

        LOG.info("deriveAndSolveCrossCorrelationData: entry, clientParameters={}, comparisonRange={}",
                 clientParameters, comparisonRange);

        final MultiProjectParameters multiProjectParameters = clientParameters.multiProject;

        final List<StackWithZValues> stackWithAllZValuesList = multiProjectParameters.buildListOfStackWithAllZ();

        final long totalNumberOfZValues = stackWithAllZValuesList.stream()
                .map(stackWithZ -> (long) stackWithZ.getzValues().size())
                .reduce(0L, Long::sum);

        final String runName = "run_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()) + "_z_corr";

        deriveCrossCorrelationData(sparkContext,
                                   clientParameters,
                                   comparisonRange,
                                   totalNumberOfZValues,
                                   multiProjectParameters,
                                   runName);

        final JavaRDD<StackWithZValues> rddStackWithAllZValues = sparkContext.parallelize(stackWithAllZValuesList);

        final JavaRDD<Integer> rddCompletedSolveCount = rddStackWithAllZValues.map(stackWithZ -> {
            LogUtilities.setupExecutorLog4j(stackWithZ.toString());
            final org.janelia.render.client.zspacing.ZPositionCorrectionClient jClient =
                    clientParameters.buildJavaClient(stackWithZ, comparisonRange, runName, true);
            final CrossCorrelationData ccData = jClient.loadCrossCorrelationDataSets();
            jClient.estimateAndSaveZCoordinates(ccData);
            return 1;
        });

        final int completedSolveCount = rddCompletedSolveCount.collect().stream().reduce(0, Integer::sum);

        LOG.info("deriveAndSolveCrossCorrelationData: exit, solved data for {} stacks", completedSolveCount);
    }

    private static void deriveCrossCorrelationData(final JavaSparkContext sparkContext,
                                                   final Parameters clientParameters,
                                                   final int comparisonRange,
                                                   final long totalNumberOfZValues,
                                                   final MultiProjectParameters multiProjectParameters,
                                                   final String runName)
            throws IOException {

        final int minBatchSizeForComparisonRange = comparisonRange * 3;
        final long longZValuesPerBatch = (totalNumberOfZValues / sparkContext.defaultParallelism()) + 1;
        final int zValuesPerBatch = Math.max(Math.toIntExact(longZValuesPerBatch), minBatchSizeForComparisonRange);

        final List<StackWithZValues> stackWithBatchedZValuesList =
                multiProjectParameters.buildListOfStackWithBatchedZ(zValuesPerBatch);

        LOG.info("deriveCrossCorrelationData: created {} batches with {} layers each for {} total layers, defaultParallelism={}",
                 stackWithBatchedZValuesList.size(),
                 zValuesPerBatch,
                 totalNumberOfZValues,
                 sparkContext.defaultParallelism());

        final JavaRDD<StackWithZValues> rddStackWithBatchedZValues = sparkContext.parallelize(stackWithBatchedZValuesList);

        final JavaRDD<Integer> rddCompletedBatchCount = rddStackWithBatchedZValues.map(stackWithZ -> {
            LogUtilities.setupExecutorLog4j(stackWithZ.toString());
            final org.janelia.render.client.zspacing.ZPositionCorrectionClient jClient =
                    clientParameters.buildJavaClient(stackWithZ, comparisonRange, runName, false);
            final CrossCorrelationData ccData = jClient.deriveCrossCorrelationData();
            jClient.saveCrossCorrelationData(ccData);
            return 1;
        });

        final int completedBatchCount = rddCompletedBatchCount.collect().stream().reduce(0, Integer::sum);

        LOG.info("deriveCrossCorrelationData: exit, generated data for {} batches", completedBatchCount);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ZPositionCorrectionClient.class);
}
