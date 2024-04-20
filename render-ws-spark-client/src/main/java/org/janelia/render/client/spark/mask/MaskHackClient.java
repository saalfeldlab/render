package org.janelia.render.client.spark.mask;

import com.beust.jcommander.ParametersDelegate;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.setup.TargetStackParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MaskHackParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.LogUtilities;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineParameters;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStep;
import org.janelia.render.client.spark.pipeline.AlignmentPipelineStepId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: merge mask option with the spark and java copy stack clients when there is more time

/**
 * Spark client for copying a stack and adding/updating a mask for all tiles in the copied stack.
 *
 * @author Eric Trautman
 */
public class MaskHackClient
        implements Serializable, AlignmentPipelineStep {

    public static class Parameters extends CommandLineParameters {
        @ParametersDelegate
        public MultiProjectParameters multiProject;

        @ParametersDelegate
        public MaskHackParameters maskHack;

        public Parameters() {
            this(new MultiProjectParameters(), new MaskHackParameters());
        }

        public Parameters(final MultiProjectParameters multiProject,
                          final MaskHackParameters maskHack) {
            this.multiProject = multiProject;
            this.maskHack = maskHack;
        }
    }

    /** Run the client with command line parameters. */
    public static void main(final String[] args) {
        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {
                final Parameters parameters = new Parameters();
                parameters.parse(args);
                final MaskHackClient client = new MaskHackClient();
                client.createContextAndRun(parameters);
            }
        };
        clientRunner.run();
    }

    /** Empty constructor required for alignment pipeline steps. */
    public MaskHackClient() {
    }

    /** Create a spark context and run the client with the specified parameters. */
    public void createContextAndRun(final Parameters clientParameters) throws IOException {
        final SparkConf conf = new SparkConf().setAppName(getClass().getSimpleName());
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            LOG.info("createContextAndRun: appId is {}", sparkContext.getConf().getAppId());
            copyStackAndSetMasks(sparkContext, clientParameters);
        }
    }

    /** Validates the specified pipeline parameters are sufficient. */
    @Override
    public void validatePipelineParameters(final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException {
        AlignmentPipelineParameters.validateRequiredElementExists("mask",
                                                                  pipelineParameters.getMaskHack());
    }

    /** Run the client as part of an alignment pipeline. */
    public void runPipelineStep(final JavaSparkContext sparkContext,
                                final AlignmentPipelineParameters pipelineParameters)
            throws IllegalArgumentException, IOException {
        // do not use naming groups because hacked masks need to be applied to different types of stacks
        final Parameters clientParameters = new Parameters(pipelineParameters.getMultiProject(null),
                                                           pipelineParameters.getMaskHack());
        copyStackAndSetMasks(sparkContext, clientParameters);
    }

    @Override
    public AlignmentPipelineStepId getDefaultStepId() {
        return AlignmentPipelineStepId.HACK_MASK;
    }

    private void copyStackAndSetMasks(final JavaSparkContext sparkContext,
                                      final Parameters clientParameters)
            throws IOException {

        LOG.info("copyStackAndSetMasks: entry, clientParameters={}", clientParameters);

        final MultiProjectParameters multiProjectParameters = clientParameters.multiProject;
        final String baseDataUrl = multiProjectParameters.getBaseDataUrl();
        final MaskHackParameters maskHack = clientParameters.maskHack;
        final List<StackWithZValues> stackWithZValuesList = multiProjectParameters.buildListOfStackWithAllZ();

        final JavaRDD<StackWithZValues> rddStackWithZValues = sparkContext.parallelize(stackWithZValuesList);

        final Integer zeroLevelKey = 0;
        final String dynamicMaskUrl = maskHack.getDynamicMaskUrl();

        final Function<StackWithZValues, Void> copyAndSetMaskFunction = stackWithZ -> {

            LogUtilities.setupExecutorLog4j(stackWithZ.toString());

            final StackId stackId = stackWithZ.getStackId();
            final RenderDataClient sourceDataClient = new RenderDataClient(baseDataUrl,
                                                                           stackId.getOwner(),
                                                                           stackId.getProject());
            final TargetStackParameters target = maskHack.buildPipelineClone(stackId).targetStack;

            final RenderDataClient targetDataClient = new RenderDataClient(baseDataUrl,
                                                                           target.owner,
                                                                           target.project);

            final StackMetaData sourceMetaData = sourceDataClient.getStackMetaData(stackId.getStack());
            targetDataClient.setupDerivedStack(sourceMetaData, target.stack);

            for (final Double z : stackWithZ.getzValues()) {

                final ResolvedTileSpecCollection resolvedTiles = sourceDataClient.getResolvedTiles(stackId.getStack(),
                                                                                                   z);
                resolvedTiles.getTileSpecs().forEach(tileSpec -> {

                    for (final ChannelSpec channelSpec : tileSpec.getAllChannels()) {

                        final Map.Entry<Integer, ImageAndMask> entry = channelSpec.getFirstMipmapEntry();

                        if ((entry != null) && zeroLevelKey.equals(entry.getKey())) {
                            final ImageAndMask sourceImageAndMask = entry.getValue();
                            final ImageAndMask updatedImageAndMask =
                                    sourceImageAndMask.copyWithMask(dynamicMaskUrl,
                                                                    ImageLoader.LoaderType.DYNAMIC_MASK,
                                                                    null);
                            channelSpec.putMipmap(zeroLevelKey, updatedImageAndMask);
                        }
                    }

                });

                targetDataClient.saveResolvedTiles(resolvedTiles, target.stack, z);
            }

            if (target.completeStack) {
                targetDataClient.setStackState(target.stack, StackMetaData.StackState.COMPLETE);
            }

            return null;
        };

        final JavaRDD<Void> rddCopyAndSetMask = rddStackWithZValues.map(copyAndSetMaskFunction);
        rddCopyAndSetMask.collect();

        LOG.info("copyStackAndSetMasks: collected rddCopyAndSetMask");
        LOG.info("copyStackAndSetMasks: exit");
    }

    private static final Logger LOG = LoggerFactory.getLogger(MaskHackClient.class);
}
