package org.janelia.render.client.newsolver;

import java.io.IOException;

import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.blocksolveparameters.FIBSEMAlignmentParameters;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common shared utilities for alternating alignment or intensity correction runs.
 */
public class AlternatingSolveUtils {

    /**
     * Tries to remove the specified stack and simply logs an error if that fails.
     */
    public static void cleanUpIntermediateStack(final RenderWebServiceParameters renderWeb,
                                                final String stack) {
        final RenderDataClient dataClient = renderWeb.getDataClient();
        try {
            dataClient.deleteStack(stack, null);
            LOG.info("cleanUpIntermediateStack: deleted stack {}", stack);
        } catch (final IOException e) {
            LOG.error("cleanUpIntermediateStack: error deleting stack {}", stack, e);
        }
    }

    public static String getStackNameForRun(final String name,
                                            final int runNumber,
                                            final int nTotalRuns) {
        return runNumber == nTotalRuns ? name : name + "_run" + runNumber;
    }

    /**
     * Update parameters for the next run in an alternating run sequence.
     *
     * @param  setupParameters   parameters to update.
     * @param  currentRunNumber  number of current run (first runNumber is 1).
     */
    public static void updateParametersForNextRun(final AffineBlockSolverSetup setupParameters,
                                                  final int currentRunNumber) {
        // alternate block layout
        // first run (runNumber 1) and all other odd number runs should be un-shifted
        setupParameters.blockPartition.shiftBlocks = (currentRunNumber % 2 == 0);

        // don't stitch or pre-align after first run
        if (currentRunNumber > 1) {
            setupParameters.stitchFirst = false;
            setupParameters.preAlign = FIBSEMAlignmentParameters.PreAlign.NONE;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(AlternatingSolveUtils.class);
}
