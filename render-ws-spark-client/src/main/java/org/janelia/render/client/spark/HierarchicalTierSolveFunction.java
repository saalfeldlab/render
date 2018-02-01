package org.janelia.render.client.spark;

import java.io.File;
import java.util.Date;
import java.util.List;

import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark function for creating all stacks in a hierarchical tier.
 *
 * @author Eric Trautman
 */
public class HierarchicalTierSolveFunction
        implements Function<HierarchicalStack, HierarchicalStack> {

    private final String baseDataUrl;
    private final Integer zNeighborDistance;
    private final Broadcast<EMAlignerTool> broadcastEMAlignerTool;

    public HierarchicalTierSolveFunction(final String baseDataUrl,
                                         final Integer zNeighborDistance,
                                         final Broadcast<EMAlignerTool> broadcastEMAlignerTool) {
        this.baseDataUrl = baseDataUrl;
        this.zNeighborDistance = zNeighborDistance;
        this.broadcastEMAlignerTool = broadcastEMAlignerTool;
    }

    @Override
    public HierarchicalStack call(final HierarchicalStack tierStack)
            throws Exception {

        final StackId splitStackId = tierStack.getSplitStackId();

        LogUtilities.setupExecutorLog4j(splitStackId.getStack());

        final Bounds bounds = tierStack.getFullScaleBounds();
        final EMAlignerTool nodeSolver = broadcastEMAlignerTool.getValue();

        final String parametersFileName = String.format("solve_%s_%s.json",
                                                        splitStackId.getStack(), new Date().getTime());
        final File parametersFile = new File(System.getProperty("spark.local.dir"), parametersFileName);

        final MatchCollectionId matchCollectionId = tierStack.getMatchCollectionId();

        nodeSolver.generateParametersFile(baseDataUrl,
                                          splitStackId,
                                          bounds.getMinZ(),
                                          bounds.getMaxZ(),
                                          matchCollectionId.getOwner(),
                                          matchCollectionId.getName(),
                                          zNeighborDistance,
                                          tierStack.getAlignedStackId(),
                                          parametersFile);

        final int returnCode = nodeSolver.run(parametersFile);

        // TODO: handle return code, how to handle missing matches? drill down one more tier? two empty tiers == stop?

        double tierStackAlignmentQuality = -1.0;

        if (returnCode == 0) {
            final StackId alignStackId = tierStack.getAlignedStackId();
            final RenderDataClient localDataClient = new RenderDataClient(baseDataUrl,
                                                                          alignStackId.getOwner(),
                                                                          alignStackId.getProject());
            final List<StackId> tierStacks = localDataClient.getProjectStacks();

            if (tierStacks.contains(alignStackId)) {

                final StackMetaData alignStackMetaData = localDataClient.getStackMetaData(alignStackId.getStack());
                final Double alignmentQuality = alignStackMetaData.getCurrentVersion().getAlignmentQuality();
                if (alignmentQuality != null) {
                    tierStackAlignmentQuality = alignmentQuality;
                } else {
                    LOG.warn("alignment quality was not saved for {}", alignStackId);
                }

            } else {
                LOG.warn("alignment stack {} does not exist", alignStackId);
            }
        }

        LOG.info("setting alignment quality {} for {}", tierStackAlignmentQuality, tierStack.getSplitStackId());

        tierStack.setAlignmentQuality(tierStackAlignmentQuality);

        return tierStack;
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalTierSolveFunction.class);

}
