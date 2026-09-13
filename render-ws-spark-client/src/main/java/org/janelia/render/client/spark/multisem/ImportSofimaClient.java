package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.SofimaParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for adding a SOFIMA displacement field to multiple stacks.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.ImportSofimaClient}.
 *
 * <p>Each stack is imported independently, so stacks are the unit of distribution here.  Within a
 * stack, the java client walks the layers in order and spreads the tiles of each layer over
 * {@code spark.executor.cores} threads.</p>
 *
 * @see org.janelia.render.client.multisem.ImportSofimaClient
 */
public class ImportSofimaClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public SofimaParameters sofima = new SofimaParameters();

        public Parameters() {
        }

        public Parameters(final MultiProjectParameters multiProject,
                          final SofimaParameters sofima) {
            this.multiProject = multiProject;
            this.sofima = sofima;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final ImportSofimaClient client = new ImportSofimaClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public ImportSofimaClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            run(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {

        final SofimaParameters sofima = pipelineParameters.getSofima();

        AlignmentPipelineParameters.validateRequiredElementExists("sofima", sofima);

        sofima.validate();
    }

    /** Runs the {@link org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId#IMPORT_SOFIMA IMPORT_SOFIMA} step. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final Parameters clientParameters = new Parameters();
        clientParameters.multiProject = pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());
        clientParameters.sofima = pipelineParameters.getSofima();
        run(sparkContext, clientParameters);
    }

    private void run(final JavaSparkContext sparkContext,
                     final Parameters clientParameters)
            throws IllegalArgumentException, IOException {

        LOG.info("run: entry, clientParameters={}", clientParameters);

        final SofimaParameters sofima = clientParameters.sofima;
        sofima.validate();

        final String baseDataUrl = clientParameters.multiProject.getBaseDataUrl();

        final List<StackWithZValues> stacksWithAllZ = clientParameters.multiProject.buildListOfStackWithAllZ();

        // fail fast for parameters that match no stacks
        if (stacksWithAllZ.isEmpty()) {
            throw new IllegalArgumentException("no stacks match parameters: " +
                                               clientParameters.multiProject.stackIdWithZ);
        }

        // The java client parallelizes the tiles of each layer, so give each task the cores of the
        // executor it lands on.  This is read on the driver because the lambda must stay serializable.
        final int numThreads = Math.max(1, sparkContext.getConf().getInt("spark.executor.cores", 1));

        final int parallelism = Math.min(MFOVAsTileClient.MAX_PARTITIONS_FOR_ONE_WEB_SERVER,
                                         stacksWithAllZ.size());

        LOG.info("run: distributing import for {} stack(s) with parallelism {} and {} thread(s) per stack (defaultParallelism={})",
                 stacksWithAllZ.size(), parallelism, numThreads, sparkContext.defaultParallelism());

        final JavaRDD<StackWithZValues> rddStacks = sparkContext.parallelize(stacksWithAllZ, parallelism);

        final Function<StackWithZValues, StackId> importFieldFunction = stackWithAllZ -> {

            final StackId sourceStackId = stackWithAllZ.getStackId();

            LogUtilities.setupExecutorLog4j(sourceStackId.toDevString());

            final StackId targetStackId = sofima.getTargetStackId(sourceStackId);

            LOG.info("importFieldFunction: entry, sourceStackId={}, targetStackId={}",
                     sourceStackId.toDevString(), targetStackId.toDevString());

            final org.janelia.render.client.multisem.ImportSofimaClient.Parameters javaClientParameters =
                    new org.janelia.render.client.multisem.ImportSofimaClient.Parameters();
            javaClientParameters.renderParams.baseDataUrl = baseDataUrl;
            javaClientParameters.renderParams.owner = sourceStackId.getOwner();
            javaClientParameters.renderParams.project = sourceStackId.getProject();
            javaClientParameters.stack = sourceStackId.getStack();
            javaClientParameters.sofima = sofima;
            javaClientParameters.numThreads = numThreads;

            final org.janelia.render.client.multisem.ImportSofimaClient javaClient =
                    new org.janelia.render.client.multisem.ImportSofimaClient(javaClientParameters);
            javaClient.addDisplacementField();

            LOG.info("importFieldFunction: exit, sourceStackId={}, targetStackId={}",
                     sourceStackId.toDevString(), targetStackId.toDevString());

            return targetStackId;
        };

        final long numImportedStacks = rddStacks.map(importFieldFunction).count();

        LOG.info("run: exit, added displacement field to {} stack(s)", numImportedStacks);
    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportSofimaClient.class);
}
