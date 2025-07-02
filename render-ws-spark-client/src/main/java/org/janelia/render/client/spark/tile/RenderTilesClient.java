package org.janelia.render.client.spark.tile;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
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

    public void renderTiles(final JavaSparkContext sparkContext,
                            final Parameters clientParameters)
            throws IOException {

        LOG.info("renderTiles: entry, clientParameters={}", clientParameters);

        final MultiProjectParameters multiProjectParameters = clientParameters.multiProject;
        final String baseDataUrl = multiProjectParameters.getBaseDataUrl();
        final TileRenderParameters tileRenderParameters = clientParameters.tileRender;

        // build a list of stacks with batched Z values since each z layer can be rendered in parallel
        final List<StackWithZValues> stackWithZValuesList = multiProjectParameters.buildListOfStackWithBatchedZ();

        // build a java client for each stack before distributing the rendering tasks just to validate parameters
        final List<org.janelia.render.client.tile.RenderTilesClient> javaStackSetupClients = new ArrayList<>();
        stackWithZValuesList.stream()
                .map(StackWithZValues::getStackId)
                .distinct()
                .sorted()
                .forEach(stackId -> {
                    final org.janelia.render.client.tile.RenderTilesClient jClient =
                            buildJavaRenderTilesClient(baseDataUrl,
                                                       stackId,
                                                       tileRenderParameters);
                    javaStackSetupClients.add(jClient);
                });

        // if hack stacks are requested, set them up before rendering using the javaStackSetupClients
        if (tileRenderParameters.hackStack != null) {
            for (final org.janelia.render.client.tile.RenderTilesClient jClient : javaStackSetupClients) {
                jClient.setupHackStackAsNeeded();
            }
        }

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Function<StackWithZValues, Integer> renderStackFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final org.janelia.render.client.tile.RenderTilesClient jClient = buildJavaRenderTilesClient(baseDataUrl,
                                                                                                        stackId,
                                                                                                        tileRenderParameters);
            jClient.renderTiles(stackWithZ.getzValues());

            return 1;
        };

        final JavaRDD<Integer> rddSummaries = rddStackWithZValues.map(renderStackFunction);
        
        final List<Integer> resultList = rddSummaries.collect();

        LOG.info("renderTiles: completed rendering for {} stacks", resultList.size());

        // if hack stacks and completion are requested, complete them after rendering
        if ((tileRenderParameters.hackStack != null) && tileRenderParameters.completeHackStack) {
            for (final org.janelia.render.client.tile.RenderTilesClient jClient : javaStackSetupClients) {
                jClient.completeHackStackAsNeeded();
            }
        }

        LOG.info("renderTiles: exit");
    }

    private static org.janelia.render.client.tile.RenderTilesClient buildJavaRenderTilesClient(final String baseDataUrl,
                                                                                               final StackId stackId,
                                                                                               final TileRenderParameters tileRenderParameters) {
        final RenderDataClient projectDataClient = new RenderDataClient(baseDataUrl,
                                                                        stackId.getOwner(),
                                                                        stackId.getProject());
        return new org.janelia.render.client.tile.RenderTilesClient(projectDataClient,
                                                                    stackId.getStack(),
                                                                    tileRenderParameters);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTilesClient.class);
}
