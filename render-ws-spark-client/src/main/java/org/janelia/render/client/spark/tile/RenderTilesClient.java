package org.janelia.render.client.spark.tile;

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
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.parameter.TileRenderParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for rendering individual tiles.  Images are placed in:
 * <pre>
 *   [rootDirectory]/[project]/[stack]/[runTimestamp]/[z-thousands]/[z-hundreds]/[z]/[tileId].[format]
 * </pre>
 */
public class RenderTilesClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject = new MultiProjectParameters();

        @ParametersDelegate
        public TileRenderParameters tileRender = new TileRenderParameters();

        public Parameters() {
        }

        public Parameters(final MultiProjectParameters multiProject,
                          final TileRenderParameters tileRender) {
            this.multiProject = multiProject;
            this.tileRender = tileRender;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final RenderTilesClient client = new RenderTilesClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public RenderTilesClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("run: appId is {}", sparkContext.getConf().getAppId());
            renderTiles(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("tileCluster",
                                                                  pipelineParameters.getTileCluster());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {

        final Parameters clientParameters = new Parameters();
        clientParameters.multiProject = pipelineParameters.getMultiProject(pipelineParameters.getRawNamingGroup());
        clientParameters.tileRender = pipelineParameters.getTileRender();

        renderTiles(sparkContext, clientParameters);
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.RENDER_TILES;
    }

    private void renderTiles(final JavaSparkContext sparkContext,
                             final Parameters clientParameters)
            throws IOException {

        LOG.info("renderTiles: entry, clientParameters={}", clientParameters);

        final MultiProjectParameters multiProjectParameters = clientParameters.multiProject;
        final String baseDataUrl = multiProjectParameters.getBaseDataUrl();
        final List<StackWithZValues> stackWithZValuesList = multiProjectParameters.buildListOfStackWithAllZ();

        // TODO: parallelize for each stack z instead of for each stack

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, Integer> renderStackFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final RenderDataClient projectDataClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());

            final org.janelia.render.client.tile.RenderTilesClient jClient =
                    new org.janelia.render.client.tile.RenderTilesClient(projectDataClient,
                                                                         stackId.getStack(),
                                                                         clientParameters.tileRender);

            jClient.collectTileInfoAndRenderTiles(stackWithZ.getzValues());

            return 1;
        };

        final JavaRDD<Integer> rddSummaries = rddStackWithZValues.map(renderStackFunction);
        
        final List<Integer> resultList = rddSummaries.collect();

        LOG.info("renderTiles: completed rendering for {} stacks", resultList.size());

        LOG.info("renderTiles: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTilesClient.class);
}
