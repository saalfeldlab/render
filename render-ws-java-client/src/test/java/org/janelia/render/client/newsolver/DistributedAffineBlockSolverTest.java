package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
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

    private static final String TEST_PROJECT = "test_mfov_as_tile";

    private static final String TEST_FOUR_STACK = "test_four_mfovs";
    private static final String TEST_FOUR_MATCH_COLLECTION = "test_four_mfovs_match";
    private static final String TEST_FOUR_ONLY_REAL_MATCH_COLLECTION = "test_four_mfovs_match_only_real";
    private static final String TEST_FOUR_MFOV_WOUT_5_TO_6_MATCH_COLLECTION = "test_four_mfovs_match_wout_5_to_6";
    private static final Set<String> TEST_FOUR_TILE_IDS = Set.of("w60_s360_r00_gc_z002_m0002",
                                                                 "w60_s360_r00_gc_z002_m0005",
                                                                 "w60_s360_r00_gc_z002_m0006",
                                                                 "w60_s360_r00_gc_z002_m0009");

    private static final String TEST_TEN_STACK = "test_ten_mfovs";
    private static final String TEST_TEN_MATCH_COLLECTION = "test_ten_mfovs_match";
    private static final String TEST_TEN_ONLY_REAL_MATCH_COLLECTION = "test_ten_mfovs_match_only_real";
    private static final Set<String> TEST_TEN_TILE_IDS = Set.of("w60_s360_r00_gc_z002_m0000",
                                                                "w60_s360_r00_gc_z002_m0002",
                                                                "w60_s360_r00_gc_z002_m0003",
                                                                "w60_s360_r00_gc_z002_m0005",
                                                                "w60_s360_r00_gc_z002_m0006",
                                                                "w60_s360_r00_gc_z002_m0007",
                                                                "w60_s360_r00_gc_z002_m0009",
                                                                "w60_s360_r00_gc_z002_m0010",
                                                                "w60_s360_r00_gc_z002_m0016",
                                                                "w60_s360_r00_gc_z002_m0017");

    private static final String TEST_ALL_STACK = "test_all_mfovs";
    private static final String TEST_ALL_MATCH_COLLECTION = "test_all_mfovs_match";
    private static final String TEST_ALL_PATCH_1EM6_MATCH_COLLECTION = "test_all_mfovs_match_patch_1em6";
    private static final String TEST_ALL_PATCH_1EM20_MATCH_COLLECTION = "test_all_mfovs_match_patch_1em20";

    public static void main(final String[] args) throws Exception {

        setupTestData();

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        final String alignSuffixWithTime = "_align_" + sdf.format(System.currentTimeMillis());

        final List<String> pointMatchExplorerUrls = List.of(

                // ------------------------------------------------------
                // 4 MFOV tests

                runAlignmentTest(TEST_PROJECT,
                                 TEST_FOUR_STACK,
                                 TEST_FOUR_MATCH_COLLECTION,
                                 alignSuffixWithTime),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_FOUR_STACK,
                                 TEST_FOUR_ONLY_REAL_MATCH_COLLECTION,
                                 alignSuffixWithTime + "_only_real"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_FOUR_STACK,
                                 TEST_FOUR_MFOV_WOUT_5_TO_6_MATCH_COLLECTION,
                                 alignSuffixWithTime + "_wout_5_to_6"),

                // ------------------------------------------------------
                // 10 MFOV tests

                runAlignmentTest(TEST_PROJECT,
                                 TEST_TEN_STACK,
                                 TEST_TEN_MATCH_COLLECTION,
                                 alignSuffixWithTime),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_TEN_STACK,
                                 TEST_TEN_ONLY_REAL_MATCH_COLLECTION,
                                 alignSuffixWithTime + "_only_real"),

                // ------------------------------------------------------
                // all MFOV tests

                runAlignmentTest(TEST_PROJECT,
                                 TEST_ALL_STACK,
                                 TEST_ALL_MATCH_COLLECTION,
                                 alignSuffixWithTime),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_ALL_STACK,
                                 TEST_ALL_PATCH_1EM6_MATCH_COLLECTION,
                                 alignSuffixWithTime + "_patch_1em6"),
                runAlignmentTest(TEST_PROJECT,
                                 TEST_ALL_STACK,
                                 TEST_ALL_PATCH_1EM20_MATCH_COLLECTION,
                                 alignSuffixWithTime + "_patch_1em20")

        );

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

        final RenderDataClient sourceDataClient = buildClient(SOURCE_PROJECT);
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        setupStack(sourceDataClient, sourceStackMetaData, TEST_FOUR_STACK, TEST_FOUR_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_TEN_STACK, TEST_TEN_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_ALL_STACK, null);

        final RenderDataClient sourceMatchClient = buildClient(SOURCE_MATCH_COLLECTION);
        final List<CanvasMatches> sourceCmList = sourceMatchClient.getMatchesWithinGroup(String.valueOf(Z));

        setupMatchCollection(sourceCmList, TEST_FOUR_MATCH_COLLECTION,
                             TEST_FOUR_TILE_IDS, null, false, null);
        setupMatchCollection(sourceCmList, TEST_FOUR_ONLY_REAL_MATCH_COLLECTION,
                             TEST_FOUR_TILE_IDS, null, true, null);
        setupMatchCollection(sourceCmList, TEST_FOUR_MFOV_WOUT_5_TO_6_MATCH_COLLECTION,
                             TEST_FOUR_TILE_IDS, null, false,
                             new OrderedCanvasIdPair(new CanvasId("2.0", "w60_s360_r00_gc_z002_m0005"),
                                                     new CanvasId("2.0", "w60_s360_r00_gc_z002_m0006"),
                                                     0.0));

        setupMatchCollection(sourceCmList, TEST_TEN_MATCH_COLLECTION,
                             TEST_TEN_TILE_IDS, null, false, null);
        setupMatchCollection(sourceCmList, TEST_TEN_ONLY_REAL_MATCH_COLLECTION,
                             TEST_TEN_TILE_IDS, null, true, null);

        setupMatchCollection(sourceCmList, TEST_ALL_MATCH_COLLECTION,
                             null, null, false, null);
        setupMatchCollection(sourceCmList, TEST_ALL_PATCH_1EM6_MATCH_COLLECTION,
                             null, 1e-6, false, null);
        setupMatchCollection(sourceCmList, TEST_ALL_PATCH_1EM20_MATCH_COLLECTION,
                             null, 1e-20, false, null);
    }

    private static void setupStack(final RenderDataClient sourceDataClient,
                                   final StackMetaData sourceStackMetaData,
                                   final String targetStackName,
                                   final Set<String> tileIds)
            throws IOException {

        final RenderDataClient testDataClient = buildClient(TEST_PROJECT);
        testDataClient.setupDerivedStack(sourceStackMetaData, targetStackName);

        final ResolvedTileSpecCollection resolvedTiles = sourceDataClient.getResolvedTiles(SOURCE_STACK, Z);
        if (tileIds != null) {
            resolvedTiles.retainTileSpecs(tileIds);
        }

        testDataClient.saveResolvedTiles(resolvedTiles, targetStackName, Z);
        testDataClient.setStackState(targetStackName, StackMetaData.StackState.COMPLETE);
    }

    private static void setupMatchCollection(final List<CanvasMatches> sourceCanvasMatchesList,
                                             final String targetCollectionName,
                                             final Set<String> tileIds,
                                             final Double patchWeight,
                                             final boolean onlyRealMatches,
                                             final OrderedCanvasIdPair excludedPair)
            throws IOException {

        List<CanvasMatches> testCanvasMatchesList = new ArrayList<>(sourceCanvasMatchesList.size());
        for (final CanvasMatches cm : sourceCanvasMatchesList) {
            if ((tileIds == null) ||
                (tileIds.contains(cm.getpId()) && tileIds.contains(cm.getqId()))) {

                testCanvasMatchesList.add(new CanvasMatches(cm.getpGroupId(),
                                                            cm.getpId(),
                                                            cm.getqGroupId(),
                                                            cm.getqId(),
                                                            cm.getMatches()));
            }
        }

        final double realMatchWeightMinimum = 0.9999;
        if (patchWeight != null) {
            final List<CanvasMatches> listWithPatchWeight = new ArrayList<>();
            for (final CanvasMatches cm : testCanvasMatchesList) {
                final double firstWeight = cm.getMatches().getWs()[0];
                if (firstWeight < realMatchWeightMinimum) {
                    final CanvasMatches updatedWeightCm = new CanvasMatches(cm.getpGroupId(), cm.getpId(),
                                                                            cm.getqGroupId(), cm.getqId(),
                                                                            cm.getMatches().withWeight(patchWeight));
                    listWithPatchWeight.add(updatedWeightCm);
                } else {
                    listWithPatchWeight.add(cm);
                }
            }
            testCanvasMatchesList = listWithPatchWeight;
        }

        if (onlyRealMatches) {
            testCanvasMatchesList = testCanvasMatchesList.stream()
                    .filter(cm -> cm.getMatches().getWs()[0] > realMatchWeightMinimum)
                    .collect(Collectors.toList());
        }

        if (excludedPair != null) {
            final String excludedP = excludedPair.getP().getId();
            final String excludedQ = excludedPair.getQ().getId();
            testCanvasMatchesList = testCanvasMatchesList.stream()
                    .filter(cm -> ! (cm.getpId().equals(excludedP) && cm.getqId().equals(excludedQ)))
                    .collect(Collectors.toList());
        }

        if (testCanvasMatchesList.isEmpty()) {
            throw new IllegalArgumentException("no matches left to save for " + targetCollectionName);
        }

        final RenderDataClient testMatchClient = buildClient(targetCollectionName);
        testMatchClient.saveMatches(testCanvasMatchesList);
    }

    private static RenderDataClient buildClient(final String projectOrCollection) {
        return new RenderDataClient(BASE_DATA_URL, OWNER, projectOrCollection);
    }
}
