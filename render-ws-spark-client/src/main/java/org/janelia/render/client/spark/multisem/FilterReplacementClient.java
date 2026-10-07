package org.janelia.render.client.spark.multisem;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.FilterReplacementParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for replacing the filter specs of each tile in one or more stacks with the filter specs
 * of the same tiles in other stacks.
 * Core logic is implemented in {@link org.janelia.render.client.multisem.FilterReplacementClient}.
 *
 * <p>Each stack is processed independently, so stacks are the unit of distribution here.</p>
 *
 * @see org.janelia.render.client.multisem.FilterReplacementClient
 */
public class FilterReplacementClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {

        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public FilterReplacementParameters filterReplacement = new FilterReplacementParameters();
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final FilterReplacementClient client = new FilterReplacementClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public FilterReplacementClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            replaceFilters(sparkContext,
                           clientParameters.multiProject.getBaseDataUrl(),
                           clientParameters.multiProject.owner,
                           clientParameters.filterReplacement);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {

        final FilterReplacementParameters filterReplacement = pipelineParameters.getFilterReplacement();

        AlignmentPipelineParameters.validateRequiredElementExists("filterReplacement",
                                                                  filterReplacement);

        filterReplacement.validate();
    }

    /** Runs the {@link org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId#REPLACE_FILTERS REPLACE_FILTERS} step. */
    @Override
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        // Tile stacks are identified by the tileStackPattern rather than by a pipeline stack group.
        // A null group does not reset the group set by an earlier step (e.g. IMPORT_SOFIMA's raw group),
        // so the multiProject parameters are only used here for the base data url and owner.
        final MultiProjectParameters multiProject = pipelineParameters.getMultiProject(null);
        replaceFilters(sparkContext,
                       multiProject.getBaseDataUrl(),
                       multiProject.owner,
                       pipelineParameters.getFilterReplacement());
    }

    public void replaceFilters(final JavaSparkContext sparkContext,
                               final String baseDataUrl,
                               final String owner,
                               final FilterReplacementParameters filterReplacement)
            throws IllegalArgumentException, IOException {

        LOG.info("replaceFilters: entry, owner={}, filterReplacement={}", owner, filterReplacement);

        filterReplacement.validate();

        final RenderDataClient ownerDataClient = new RenderDataClient(baseDataUrl, owner, "not_used");
        final List<StackId> tileStackIds = ownerDataClient.getOwnerStacks().stream()
                .filter(stackId -> filterReplacement.isValidStack(stackId.getStack()))
                .collect(Collectors.toList());

        // fail fast for parameters that match no stacks
        if (tileStackIds.isEmpty()) {
            throw new IllegalArgumentException("none of the stacks for owner " + owner +
                                               " match the tileStackPattern '" +
                                               filterReplacement.tileStackPattern + "'");
        }

        // derive every filter and target stack before replacing anything so that
        // a bad pattern or replacement fails before any work is done
        for (final StackId tileStackId : tileStackIds) {
            filterReplacement.getTargetStackId(tileStackId); // also derives the filter stack
        }

        final int parallelism = Math.min(MFOVAsTileClient.MAX_PARTITIONS_FOR_ONE_WEB_SERVER,
                                         tileStackIds.size());

        LOG.info("replaceFilters: distributing replacement for {} stack(s) with parallelism {} (defaultParallelism={})",
                 tileStackIds.size(), parallelism, sparkContext.defaultParallelism());

        final JavaRDD<StackId> rddTileStackIds = sparkContext.parallelize(tileStackIds, parallelism);

        final Function<StackId, StackId> replaceFiltersFunction = tileStackId -> {

            LogUtilities.setupExecutorLog4j(tileStackId.toDevString());

            final RenderDataClient dataClient = new RenderDataClient(baseDataUrl,
                                                                     tileStackId.getOwner(),
                                                                     tileStackId.getProject());

            final org.janelia.render.client.multisem.FilterReplacementClient javaClient =
                    new org.janelia.render.client.multisem.FilterReplacementClient();
            javaClient.replaceFilters(dataClient, tileStackId.getStack(), filterReplacement);

            return filterReplacement.getTargetStackId(tileStackId);
        };

        final long numReplacedStacks = rddTileStackIds.map(replaceFiltersFunction).count();

        LOG.info("replaceFilters: exit, replaced filters for {} stack(s)", numReplacedStacks);
    }

    private static final Logger LOG = LoggerFactory.getLogger(FilterReplacementClient.class);
}
