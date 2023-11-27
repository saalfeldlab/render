package org.janelia.render.client.spark.pipeline;

import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.match.ConnectedTileClusterSummaryForStack;
import org.janelia.alignment.multisem.UnconnectedMFOVPairsForStack;
import org.janelia.alignment.spec.stack.StackWithZValues;
import org.janelia.render.client.ClientRunner;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.AlignmentPipelineParameters;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.MultiProjectParameters;
import org.janelia.render.client.spark.MipmapClient;
import org.janelia.render.client.spark.match.ClusterCountClient;
import org.janelia.render.client.spark.match.CopyMatchClient;
import org.janelia.render.client.spark.match.MultiStagePointMatchClient;
import org.janelia.render.client.spark.multisem.MFOVMontageMatchPatchClient;
import org.janelia.render.client.spark.multisem.UnconnectedCrossMFOVClient;
import org.janelia.render.client.spark.newsolver.DistributedAffineBlockSolverClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark client for running various alignment stages in one go.
 * Parameters for the alignment stages to run are defined in a JSON file.
 *
 * @author Eric Trautman
 */
public class AlignmentPipelineClient
        implements Serializable {

    public static class Parameters extends CommandLineParameters {
        @Parameter(
                names = "--pipelineJson",
                description = "JSON file where pipeline parameters are defined",
                required = true)
        public String pipelineJson;
    }

    public static void main(final String[] args) {

        final ClientRunner clientRunner = new ClientRunner(args) {
            @Override
            public void runClient(final String[] args) throws Exception {

                final Parameters parameters = new Parameters();
                parameters.parse(args);

                LOG.info("runClient: entry, parameters={}", parameters);

                final AlignmentPipelineClient client = new AlignmentPipelineClient(parameters);
                client.run();

            }
        };
        clientRunner.run();

    }

    private final Parameters parameters;

    private AlignmentPipelineClient(final Parameters parameters) throws IllegalArgumentException {
        this.parameters = parameters;
    }

    public void run() throws IOException {

        final SparkConf conf = new SparkConf().setAppName("AlignmentPipelineClient");

        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {
            final String sparkAppId = sparkContext.getConf().getAppId();
            LOG.info("run: appId is {}", sparkAppId);
            final AlignmentPipelineParameters parsedParameters =
                    AlignmentPipelineParameters.fromJsonFile(parameters.pipelineJson);
            runWithContext(sparkContext, parsedParameters);
        }
    }

    public void runWithContext(final JavaSparkContext sparkContext,
                               final AlignmentPipelineParameters alignmentPipelineParameters)
            throws IOException, IllegalStateException {

        LOG.info("runWithContext: entry, alignmentPipelineParameters={}", alignmentPipelineParameters.toJson());

        if (alignmentPipelineParameters.hasMipmapParameters()) {
            final MipmapClient.Parameters p =
                    MipmapClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final MipmapClient mipmapClient = new MipmapClient(p);
            mipmapClient.runWithContext(sparkContext);
        }

        if (alignmentPipelineParameters.hasMatchParameters()) {
            final MultiStagePointMatchClient.Parameters p =
                    MultiStagePointMatchClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final MultiStagePointMatchClient matchClient = new MultiStagePointMatchClient(p);
            final MultiProjectParameters multiProject = alignmentPipelineParameters.getMultiProject();
            final List<StackWithZValues> batchedList = multiProject.buildListOfStackWithBatchedZ();
            matchClient.generatePairsAndMatchesForRunList(sparkContext,
                                                          batchedList,
                                                          alignmentPipelineParameters.getMatchRunList());
        }

        if (alignmentPipelineParameters.hasMfovMontagePatchParameters()) {
            final MFOVMontageMatchPatchClient.Parameters p =
                    MFOVMontageMatchPatchClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final MFOVMontageMatchPatchClient matchPatchClient = new MFOVMontageMatchPatchClient(p);
            matchPatchClient.patchPairs(sparkContext, alignmentPipelineParameters.getMfovMontagePatch());
        }

        if (alignmentPipelineParameters.hasUnconnectedCrossMfovParameters()) {
            final UnconnectedCrossMFOVClient.Parameters p =
                    UnconnectedCrossMFOVClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final UnconnectedCrossMFOVClient unconnectedMfovClient = new UnconnectedCrossMFOVClient(p);

            final List<UnconnectedMFOVPairsForStack> unconnectedMFOVsForAllStacks =
                    unconnectedMfovClient.findUnconnectedMFOVs(sparkContext);

            if (! unconnectedMFOVsForAllStacks.isEmpty()) {
                final String errorMessage =
                        "found " + unconnectedMFOVsForAllStacks.size() + " stacks with unconnected MFOVs";
                LOG.error("runWithContext: {}: {}", errorMessage, unconnectedMFOVsForAllStacks);
                throw new IllegalStateException(errorMessage);
            } else {
                LOG.info("runWithContext: all MFOVs in all stacks are connected");
            }
        }

        if (alignmentPipelineParameters.hasTileClusterParameters()) {
            final ClusterCountClient.Parameters p =
                    ClusterCountClient.Parameters.fromPipeline(alignmentPipelineParameters);
            final ClusterCountClient clusterCountClient = new ClusterCountClient(p);

            final List<ConnectedTileClusterSummaryForStack> summaryList =
                    clusterCountClient.findConnectedClusters(sparkContext);

            final List<String> problemStackSummaryStrings = new ArrayList<>();
            for (final ConnectedTileClusterSummaryForStack stackSummary : summaryList) {
                if (stackSummary.hasMultipleClusters() ||
                    stackSummary.hasUnconnectedTiles() ||
                    stackSummary.hasTooManyConsecutiveUnconnectedEdges()) {
                    problemStackSummaryStrings.add(stackSummary.toString());
                }
            }

            if (! problemStackSummaryStrings.isEmpty()) {
                throw new IllegalStateException("The following " + problemStackSummaryStrings.size() +
                                                " stacks have match connection issues:\n" +
                                                String.join("\n", problemStackSummaryStrings));
            }
        }

        if (alignmentPipelineParameters.hasMatchCopyParameters()) {
            final CopyMatchClient.Parameters p =
                    new CopyMatchClient.Parameters(alignmentPipelineParameters.getMultiProject(),
                                                   alignmentPipelineParameters.getMatchCopy());
            final CopyMatchClient copyMatchClient = new CopyMatchClient(p);
            copyMatchClient.copyMatches(sparkContext);
        }

        if (alignmentPipelineParameters.hasAffineBlockSolverSetup()) {
            runAffineBlockSolver(sparkContext, alignmentPipelineParameters);
        }

        LOG.info("runWithContext: exit");
    }

    private static void runAffineBlockSolver(final JavaSparkContext sparkContext,
                                             final AlignmentPipelineParameters alignmentPipelineParameters)
            throws IOException {

        final MultiProjectParameters multiProject = alignmentPipelineParameters.getMultiProject();
        final List<AffineBlockSolverSetup> setupList = new ArrayList<>();
        final AffineBlockSolverSetup setup = alignmentPipelineParameters.getAffineBlockSolverSetup();
        final List<StackWithZValues> stackList = multiProject.buildListOfStackWithAllZ();

        // TODO: push StackWithZValues idea into core solver code
        for (final StackWithZValues stackWithZValues : stackList) {
            setup.setValuesFromPipeline(multiProject.getBaseDataUrl(),
                                        stackWithZValues.getStackId());
            setupList.add(setup.clone());
        }

        final DistributedAffineBlockSolverClient affineBlockSolverClient = new DistributedAffineBlockSolverClient();

        if (setup.alternatingRuns < 2) {

            affineBlockSolverClient.runWithContext(sparkContext, setupList);

        } else {

            // TODO: handle alternatingRuns for multiple stacks
            if (stackList.size() > 1) {
                throw new IllegalArgumentException("alternatingRuns is not supported for multiple stacks");
            }

            final AffineBlockSolverSetup updatedSetup = setupList.get(0);
            String sourceStack = updatedSetup.stack;
            final String originalTargetStack = updatedSetup.targetStack.stack;

            for (int runNumber = 0; runNumber < updatedSetup.alternatingRuns; runNumber++) {

                final String targetStack = originalTargetStack + "_run" + runNumber;

                final AffineBlockSolverSetup runSetup = updatedSetup.clone();
                runSetup.stack = sourceStack;
                runSetup.targetStack.stack = targetStack;
                runSetup.blockPartition.shiftBlocks = runNumber % 2 == 1;

                LOG.info("runAffineBlockSolver: run {} of {}, stack={}, targetStack={}, shiftBlocks={}",
                         (runNumber + 1), runSetup.alternatingRuns, sourceStack, targetStack, runSetup.blockPartition.shiftBlocks);

                affineBlockSolverClient.runWithContext(sparkContext, Collections.singletonList(runSetup));

                sourceStack = runSetup.targetStack.stack;
            }

        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(AlignmentPipelineClient.class);
}
