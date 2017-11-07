package org.janelia.render.client.spark;

import java.io.File;
import java.util.Date;

import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.stack.HierarchicalStack;
import org.janelia.alignment.spec.stack.StackId;

/**
 * Spark function for creating all stacks in a hierarchical tier.
 *
 * @author Eric Trautman
 */
public class HierarchicalTierSolveFunction
        implements Function<HierarchicalStack, HierarchicalStack> {

    private final String baseDataUrl;
    private final Broadcast<EMAlignerTool> broadcastEMAlignerTool;

    public HierarchicalTierSolveFunction(final String baseDataUrl,
                                         final Broadcast<EMAlignerTool> broadcastEMAlignerTool) {
        this.baseDataUrl = baseDataUrl;
        this.broadcastEMAlignerTool = broadcastEMAlignerTool;
    }

    @Override
    public HierarchicalStack call(final HierarchicalStack splitStack)
            throws Exception {

        final StackId splitStackId = splitStack.getSplitStackId();

        LogUtilities.setupExecutorLog4j(splitStackId.getStack());

        final Bounds bounds = splitStack.getFullScaleBounds();
        final EMAlignerTool nodeSolver = broadcastEMAlignerTool.getValue();

        final String parametersFileName = String.format("solve_%s_%s.json",
                                                        splitStackId.getStack(), new Date().getTime());
        final File parametersFile = new File(System.getProperty("spark.local.dir"), parametersFileName);

        final MatchCollectionId matchCollectionId = splitStack.getMatchCollectionId();

        nodeSolver.generateParametersFile(baseDataUrl,
                                          splitStackId,
                                          bounds.getMinZ(),
                                          bounds.getMaxZ(),
                                          matchCollectionId.getOwner(),
                                          matchCollectionId.getName(),
                                          splitStack.getAlignedStackId(),
                                          parametersFile);

        final int returnCode = nodeSolver.run(parametersFile);

        // TODO: handle return code, how to handle missing matches? drill down one more tier? two empty tiers == stop?

        // TODO: properly calculate alignment quality - hack here just saves return code (pass/fail)
        splitStack.setAlignmentQuality((double) returnCode);

        return splitStack;
    }
}
