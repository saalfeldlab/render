package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
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

    private static final String RENDER_DATA_HOST = "renderer-dev.int.janelia.org";
    private static final String BASE_DATA_URL = "http://" + RENDER_DATA_HOST + ":8080/render-ws/v1";
    private static final String OWNER = "hess_wafers_60_61";

    private static final String SOURCE_PROJECT = "w60_serial_360_to_369";
    private static final String SOURCE_STACK = "w60_s360_r00_gc20250808a_mat_render_z_2";
    private static final double Z = 2.0;
    private static final String SOURCE_MATCH_COLLECTION = "w60_s360_r00_gc20250808a_mat_render_match";

    private static final String TEST_PROJECT = "mfov_as_tile_test";

    private static final String TEST_FOUR_STACK = "mfovs_02_05_06_09";
    private static final String TEST_FOUR_REAL_AND_FAKE_MATCH_COLLECTION = "mfovs_02_05_06_09_match_real_and_fake";
    private static final String TEST_FOUR_REAL_ONLY_MATCH_COLLECTION = "mfovs_02_05_06_09_match_real_only";
    private static final String TEST_FOUR_MFOV_REAL_AND_FAKE_WOUT_5_TO_6_MATCH_COLLECTION = "mfovs_02_05_06_09_match_real_and_fake_wout_5_to_6";

    private static final String TEST_ALL_STACK = "all_mfovs";
    private static final String TEST_ALL_REAL_AND_FAKE_MATCH_COLLECTION = "all_mfovs_match_real_and_fake";
    private static final String TEST_ALL_1EM6_WEIGHT_MATCH_COLLECTION = "all_mfovs_match_1em6_weight";
    private static final String TEST_ALL_1EM20_WEIGHT_MATCH_COLLECTION = "all_mfovs_match_1em20_weight";

    public static void main(final String[] args) throws Exception {

        // Data has already been set up, so leave the following line commented out unless you really want to set up again
        // setupTestData();

        final SimpleDateFormat sdf = new SimpleDateFormat("'_test'MMddHHmmss");
        final String testTime = sdf.format(System.currentTimeMillis());

        final List<String> pointMatchExplorerUrls = List.of(
                runAlignmentTest(TEST_PROJECT,
                                 TEST_FOUR_STACK,
                                 TEST_FOUR_REAL_AND_FAKE_MATCH_COLLECTION,
                                 testTime + "_real_and_fake"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_FOUR_STACK,
                                 TEST_FOUR_REAL_ONLY_MATCH_COLLECTION,
                                 testTime + "_real_only"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_FOUR_STACK,
                                 TEST_FOUR_MFOV_REAL_AND_FAKE_WOUT_5_TO_6_MATCH_COLLECTION,
                                 testTime + "_wout_5_to_6"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_ALL_STACK,
                                 TEST_ALL_REAL_AND_FAKE_MATCH_COLLECTION,
                                 testTime + "_real_and_fake"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_ALL_STACK,
                                 TEST_ALL_1EM6_WEIGHT_MATCH_COLLECTION,
                                 testTime + "_real_and_fake_1em6"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_ALL_STACK,
                                 TEST_ALL_1EM20_WEIGHT_MATCH_COLLECTION,
                                 testTime + "_real_and_fake_1em20")        );

        System.out.println("\n\nTo view in Point Match Explorer:\n");
        for (final String pointMatchExplorerUrl : pointMatchExplorerUrls) {
            System.out.println("  " + pointMatchExplorerUrl + "\n");
        }
    }

    private static String runAlignmentTest(final String project,
                                           final String stack,
                                           final String matchCollection,
                                           final String alignedStackSuffix) throws Exception {

        final String[] testArgs = {
                "--baseDataUrl", BASE_DATA_URL,
                "--owner", OWNER,
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

        final String pmeBase = BASE_DATA_URL.replace("/v1", "/view/point-match-explorer.html");
        final String dynamicRenderHostAndPort = "renderer.int.janelia.org:8080";
        final String pmeQuery = "?renderStackOwner=hess_wafers_60_61" +
                "&dynamicRenderHost=" + dynamicRenderHostAndPort +
                "&matchOwner=" + OWNER +
                "&renderDataHost=" + RENDER_DATA_HOST + "%3A8080" +
                "&startZ=2&endZ=2" +
                "&renderStackProject=" + project +
                "&renderStack=" + solverSetup.targetStack.stack +
                "&matchCollection=" + matchCollection;

         return  pmeBase + pmeQuery;
    }

    @SuppressWarnings("unused")
    private static void setupTestData()
            throws IOException {

        // --------------------------------------------------------------
        // setup test stack with 4 MFOV-as-tiles

        final RenderDataClient sourceDataClient = new RenderDataClient(BASE_DATA_URL, OWNER, SOURCE_PROJECT);
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        final RenderDataClient testDataClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_PROJECT);
        testDataClient.setupDerivedStack(sourceStackMetaData, TEST_FOUR_STACK);

        ResolvedTileSpecCollection resolvedTiles = sourceDataClient.getResolvedTiles(SOURCE_STACK, Z);
        final Set<String> tileIdsToKeep = Set.of("w60_s360_r00_gc_z002_m0002",
                                                 "w60_s360_r00_gc_z002_m0005",
                                                 "w60_s360_r00_gc_z002_m0006",
                                                 "w60_s360_r00_gc_z002_m0009");
        resolvedTiles.retainTileSpecs(tileIdsToKeep);

        testDataClient.saveResolvedTiles(resolvedTiles, TEST_FOUR_STACK, Z);
        testDataClient.setStackState(TEST_FOUR_STACK, StackMetaData.StackState.COMPLETE);

        // --------------------------------------------------------------
        // setup test stack with all z 2 MFOV-as-tiles

        testDataClient.setupDerivedStack(sourceStackMetaData, TEST_ALL_STACK);

        resolvedTiles = sourceDataClient.getResolvedTiles(SOURCE_STACK, Z);
        testDataClient.saveResolvedTiles(resolvedTiles, TEST_ALL_STACK, Z);
        testDataClient.setStackState(TEST_ALL_STACK, StackMetaData.StackState.COMPLETE);

        // --------------------------------------------------------------
        // setup match collections for different 4 MFOV-as-tile tests

        final RenderDataClient sourceMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, SOURCE_MATCH_COLLECTION);
        final List<CanvasMatches> sourceCanvasMatchesList = sourceMatchClient.getMatchesWithinGroup(String.valueOf(Z));

        // real and fake matches
        final List<CanvasMatches> testCanvasMatchesListRealAndFake =
                sourceCanvasMatchesList.stream()
                        .filter(cm ->
                                        tileIdsToKeep.contains(cm.getpId()) &&
                                        tileIdsToKeep.contains(cm.getqId()))
                        .collect(Collectors.toList());
        RenderDataClient testMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_FOUR_REAL_AND_FAKE_MATCH_COLLECTION);
        testMatchClient.saveMatches(testCanvasMatchesListRealAndFake);

        // real only matches
        final List<CanvasMatches> testCanvasMatchesListRealOnly =
                testCanvasMatchesListRealAndFake.stream()
                        .filter(cm -> cm.getMatches().getWs()[0] > 0.99)
                        .collect(Collectors.toList());
        testMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_FOUR_REAL_ONLY_MATCH_COLLECTION);
        testMatchClient.saveMatches(testCanvasMatchesListRealOnly);

        // real and fake matches without M0005 to M0006 real matches
        final List<CanvasMatches> testCanvasMatchesListRealAndFakeWout5To6 =
                testCanvasMatchesListRealAndFake.stream()
                        .filter(cm -> !(cm.getpId().equals("w60_s360_r00_gc_z002_m0005") &&
                                        cm.getqId().equals("w60_s360_r00_gc_z002_m0006")))
                        .collect(Collectors.toList());
        testMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_FOUR_MFOV_REAL_AND_FAKE_WOUT_5_TO_6_MATCH_COLLECTION);
        testMatchClient.saveMatches(testCanvasMatchesListRealAndFakeWout5To6);

        // --------------------------------------------------------------
        // setup match collections for different all MFOV-as-tile tests

        testMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_ALL_REAL_AND_FAKE_MATCH_COLLECTION);
        testMatchClient.saveMatches(sourceCanvasMatchesList);

        // change patch match weights from 1e-3 to 1e-6
        final List<CanvasMatches> testCanvasMatches1EM6Weight = new ArrayList<>();
        for (final CanvasMatches cm : sourceCanvasMatchesList) {
            final CanvasMatches lowerWeightCm = new CanvasMatches(cm.getpGroupId(), cm.getpId(),
                                                                  cm.getqGroupId(), cm.getqId(),
                                                                  cm.getMatches().withWeight(1e-6));
            testCanvasMatches1EM6Weight.add(cm);
        }

        testMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_ALL_1EM6_WEIGHT_MATCH_COLLECTION);
        testMatchClient.saveMatches(testCanvasMatches1EM6Weight);

        // change patch match weights from 1e-3 to 1e-20
        final List<CanvasMatches> testCanvasMatches1EM20Weight = new ArrayList<>();
        for (final CanvasMatches cm : sourceCanvasMatchesList) {
            final CanvasMatches lowerWeightCm = new CanvasMatches(cm.getpGroupId(), cm.getpId(),
                                                                  cm.getqGroupId(), cm.getqId(),
                                                                  cm.getMatches().withWeight(1e-20));
            testCanvasMatches1EM20Weight.add(cm);
        }

        testMatchClient = new RenderDataClient(BASE_DATA_URL, OWNER, TEST_ALL_1EM20_WEIGHT_MATCH_COLLECTION);
        testMatchClient.saveMatches(testCanvasMatches1EM20Weight);

    }
}
