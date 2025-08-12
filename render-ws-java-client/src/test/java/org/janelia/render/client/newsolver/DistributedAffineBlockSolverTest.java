package org.janelia.render.client.newsolver;

import java.text.SimpleDateFormat;

import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.MFOVAsTileParameters;

/**
 * Tests the {@link DistributedAffineBlockSolver} class.
 */
@SuppressWarnings("SameParameterValue")
public class DistributedAffineBlockSolverTest {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    @SuppressWarnings("unused")
    public static void main(final String[] args) throws Exception {

        final String stackWithAllZ2 = "w60_s360_r00_gc20250808a_mat_render_z_2";
        final String stackWithOnlyRealConnectedZ2 = "w60_s360_r00_gc20250808a_mat_render_z_2c";

        final String matchCollectionWithRealAndFake = "w60_s360_r00_gc20250808a_mat_render_match";
        final String matchCollectionWithOnlyReal = "w60_s360_r00_gc20250808a_mat_render_match_no_patch";
        final String matchCollectionWithoutM005Real = "w60_s360_r00_gc20250808a_mat_render_match_cx1";

        runAlignmentTest(
                stackWithAllZ2,                  // TODO: change stack as needed (see options above)
                matchCollectionWithRealAndFake); // TODO: change match collection as needed (see options above)
    }

    private static void runAlignmentTest(final String stack,
                                         final String matchCollection) throws Exception {

        final SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmmss");
        final String alignedStackSuffix = "_align_" + sdf.format(System.currentTimeMillis());

        final String renderDataHost = "renderer-dev.int.janelia.org";
        final String baseDataUrl = "http://" + renderDataHost + ":8080/render-ws/v1";
        final String owner = "hess_wafers_60_61";
        final String project = "w60_serial_360_to_369";
        final String[] testArgs = {
                "--baseDataUrl", baseDataUrl,
                "--owner", owner,
                "--project", project,
                "--matchCollection", matchCollection,
                "--stack", stack,
                "--completeTargetStack"
        };

        final AffineBlockSolverSetup cmdLineSetup = new AffineBlockSolverSetup();
        cmdLineSetup.parse(testArgs);

        final MFOVAsTileParameters mfovAsTileParameters =
                new MFOVAsTileParameters(0.2,
                                         "/tmp",
                                         "_mat",
                                         "_render",
                                         alignedStackSuffix,
                                         "_rough");

        final AffineBlockSolverSetup solverSetup = mfovAsTileParameters.buildMfovAffineBlockSolverSetup();
        solverSetup.renderWeb = cmdLineSetup.renderWeb;
        solverSetup.stack = cmdLineSetup.stack;
        solverSetup.matches = cmdLineSetup.matches;
        solverSetup.targetStack.stack = solverSetup.stack + alignedStackSuffix;
        solverSetup.targetStack.completeStack = true;

        DistributedAffineBlockSolver.run(solverSetup);

        final String pmeBase = baseDataUrl.replace("/v1", "/view/point-match-explorer.html");
        final String dynamicRenderHostAndPort = "renderer.int.janelia.org:8080";
        final String pmeQuery = "?renderStackOwner=hess_wafers_60_61" +
                "&dynamicRenderHost=" + dynamicRenderHostAndPort +
                "&matchOwner=" + owner +
                "&renderDataHost=" + renderDataHost + "%3A8080" +
                "&startZ=2&endZ=2" +
                "&renderStackProject=" + project +
                "&renderStack=" + solverSetup.targetStack.stack +
                "&matchCollection=" + matchCollection;

        System.out.println("\nTo view in Point Match Explorer:\n" + pmeBase + pmeQuery);
    }
}
