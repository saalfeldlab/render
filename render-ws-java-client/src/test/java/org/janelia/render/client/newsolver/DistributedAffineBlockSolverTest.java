package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.render.client.RenderDataClient;
import org.janelia.render.client.newsolver.setup.AffineBlockSolverSetup;
import org.janelia.render.client.parameter.MFOVAsTileParameters;

/**
 * Tests the {@link DistributedAffineBlockSolver} class.
 */
@SuppressWarnings({"SameParameterValue", "unused"})
public class DistributedAffineBlockSolverTest {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static final String RENDER_DATA_HOST = "renderer-dev.int.janelia.org";
    public static final String BASE_DATA_URL = "http://" + RENDER_DATA_HOST + ":8080/render-ws/v1";
    public static final String OWNER = "hess_wafers_60_61";

    private static final String SOURCE_PROJECT = "w60_serial_360_to_369";
    private static final String SOURCE_STACK = "w60_s360_r00_gc20250808a_mat_render_z_2";
    private static final List<Double> Z_VALUES = List.of(2.0);
    private static final String SOURCE_MATCH_COLLECTION = "w60_s360_r00_gc20250808a_mat_render_match";

    private static final boolean PRINT_PME_LINKS = false; // set to true to print Point Match Explorer links

    private static final String TEST_PROJECT = "test_mfov_as_tile";

    private static final String TEST_TWO_STACK = "test_two_mfovs";
    private static final String TEST_TWO_ONLY_REAL_MATCH_COLLECTION = "test_two_mfovs_match_only_real";
    private static final Set<String> TEST_TWO_TILE_IDS = Set.of("w60_s360_r00_gc_z002_m0005",
                                                                "w60_s360_r00_gc_z002_m0006");

    private static final String TEST_THREE_STACK = "test_three_mfovs";
    private static final String TEST_THREE_MATCH_COLLECTION = "test_three_mfovs_match";
    private static final String TEST_THREE_ONLY_REAL_MATCH_COLLECTION = "test_three_mfovs_match_only_real";
    private static final Set<String> TEST_THREE_TILE_IDS = Set.of("w60_s360_r00_gc_z002_m0002",
                                                                  "w60_s360_r00_gc_z002_m0005",
                                                                  "w60_s360_r00_gc_z002_m0006");

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

        // setupTestInputData(); // TODO: uncomment to setup test input data

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String alignSuffixWithTime = "_align_10000i_" + sdf.format(System.currentTimeMillis());

        // LogbackTestTools.setRootLogLevelToError(); // hide all logging

        // TODO: uncomment to run one more of these tests
//        final String[][] debugTestArgs = {
//                { TEST_TWO_STACK, TEST_TWO_ONLY_REAL_MATCH_COLLECTION, alignSuffixWithTime + "_only_real" },
//                { TEST_THREE_STACK, TEST_THREE_MATCH_COLLECTION, alignSuffixWithTime },
//                { TEST_THREE_STACK, TEST_THREE_ONLY_REAL_MATCH_COLLECTION, alignSuffixWithTime + "_only_real" },
//                { TEST_FOUR_STACK, TEST_FOUR_MATCH_COLLECTION, alignSuffixWithTime },
//                { TEST_FOUR_STACK, TEST_FOUR_ONLY_REAL_MATCH_COLLECTION, alignSuffixWithTime + "_only_real" }
//        };
//        for (final String[] testArgs : debugTestArgs) {
//            debugInconsistentAlignments(TEST_PROJECT,
//                                        testArgs[0],
//                                        Z_VALUES,
//                                        testArgs[1],
//                                        new char[]{'a', 'b', 'c', 'd', 'e'},
//                                        testArgs[2],
//                                        true);
//        }

        // runFourMFOVAlignmentTests(alignSuffixWithTime);
        // runTenMFOVAlignmentTests(alignSuffixWithTime);
        // runAllMFOVAlignmentTests(alignSuffixWithTime);
    }

    public static void debugInconsistentAlignments(final String testProject,
                                                   final String testSourceStack,
                                                   final List<Double> zValues,
                                                   final String matchCollection,
                                                   final char[] testIds,
                                                   final String alignSuffixWithTime,
                                                   final boolean deleteAlignStackAfterTest)
            throws Exception {

        final List<String> alignStackNames =
                runRepeatedAlignmentTests(testProject,
                                          testSourceStack,
                                          matchCollection,
                                          alignSuffixWithTime,
                                          testIds);

        final RenderDataClient testDataClient = buildClient(testProject);
        final Map<String, List<Integer>> tileIdToXOffsets = new HashMap<>();
        final Map<String, List<Integer>> tileIdToYOffsets = new HashMap<>();
        for (final String alignStack : alignStackNames) {

            for (final double z : zValues) {
                final ResolvedTileSpecCollection resolvedTiles = testDataClient.getResolvedTiles(alignStack, z);
                final List<TileBounds> tileBounds = resolvedTiles.getTileSpecs().stream()
                        .map(TileSpec::toTileBounds)
                        .sorted(Comparator.comparing(TileBounds::getTileId))
                        .collect(Collectors.toList());

                for (final TileBounds tb : tileBounds) {
                    final TileSpec tileSpec = resolvedTiles.getTileSpec(tb.getTileId());
                    final LeafTransformSpec leafTransformSpec = (LeafTransformSpec) tileSpec.getLastTransform();
                    final String[] transformData = leafTransformSpec.getDataString().split(" ");
                    final List<Integer> xOffsetList =
                            tileIdToXOffsets.computeIfAbsent(tb.getTileId(), k -> new ArrayList<>());
                    xOffsetList.add((int) Double.parseDouble(transformData[4]));
                    final List<Integer> yOffsetList =
                            tileIdToYOffsets.computeIfAbsent(tb.getTileId(), k -> new ArrayList<>());
                    yOffsetList.add((int) Double.parseDouble(transformData[5]));
                }
            }

            if (deleteAlignStackAfterTest) {
                testDataClient.setStackState(alignStack, StackMetaData.StackState.LOADING);
                testDataClient.deleteStack(alignStack, null);
            }
        }

        System.out.println("\n\nResults for " + alignSuffixWithTime + " with collection " + matchCollection);
        for (final String tileId : tileIdToXOffsets.keySet().stream().sorted().collect(Collectors.toList())) {
            final List<Integer> xOffsets = tileIdToXOffsets.get(tileId);
            final List<Integer> yOffsets = tileIdToYOffsets.get(tileId);
            System.out.println(tileId + " - xOffsets: " + xOffsets + ", yOffsets: " + yOffsets);
        }
        System.out.println("\n\n");
    }

    private static void runFourMFOVAlignmentTests(final String alignSuffixWithTime)
            throws Exception {

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_FOUR_STACK,
                                  TEST_FOUR_MATCH_COLLECTION,
                                  alignSuffixWithTime,
                                  new char[]{'a', 'b', 'c'});

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_FOUR_STACK,
                                  TEST_FOUR_ONLY_REAL_MATCH_COLLECTION,
                                  alignSuffixWithTime + "_only_real",
                                  new char[]{'a', 'b', 'c'});

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_FOUR_STACK,
                                  TEST_FOUR_MFOV_WOUT_5_TO_6_MATCH_COLLECTION,
                                  alignSuffixWithTime + "_wout_5_to_6",
                                  new char[]{'a', 'b', 'c'});
    }

    private static void runTenMFOVAlignmentTests(final String alignSuffixWithTime)
            throws Exception {

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_TEN_STACK,
                                  TEST_TEN_MATCH_COLLECTION,
                                  alignSuffixWithTime,
                                  new char[]{'a', 'b', 'c'});

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_TEN_STACK,
                                  TEST_TEN_ONLY_REAL_MATCH_COLLECTION,
                                  alignSuffixWithTime + "_only_real",
                                  new char[]{'a', 'b', 'c'});
    }

    private static void runAllMFOVAlignmentTests(final String alignSuffixWithTime)
            throws Exception {

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_ALL_STACK,
                                  TEST_ALL_MATCH_COLLECTION,
                                  alignSuffixWithTime,
                                  new char[] {'a', 'b', 'c'});

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_ALL_STACK,
                                  TEST_ALL_PATCH_1EM6_MATCH_COLLECTION,
                                  alignSuffixWithTime + "_patch_1em6",
                                  new char[] {'a', 'b', 'c'});

        runRepeatedAlignmentTests(TEST_PROJECT,
                                  TEST_ALL_STACK,
                                  TEST_ALL_PATCH_1EM20_MATCH_COLLECTION,
                                  alignSuffixWithTime + "_patch_1em20",
                                  new char[] {'a', 'b', 'c'});
    }

    public static List<String> runRepeatedAlignmentTests(final String project,
                                                         final String stack,
                                                         final String matchCollection,
                                                         final String alignedStackSuffix,
                                                         final char[] testIds) throws Exception {
        final List<String> alignedStackNames = new ArrayList<>(testIds.length);
        for (final char testId : testIds) {
            final String alignedStackName = runAlignmentTest(project,
                                                             stack,
                                                             matchCollection,
                                                             alignedStackSuffix + testId);
            alignedStackNames.add(alignedStackName);
        }
        return alignedStackNames;
    }

    public static String runAlignmentTest(final String project,
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
                                         "_prealign",
                                         "_mat",
                                         "_render",
                                         alignedStackSuffix,
                                         "_rough");

        final AffineBlockSolverSetup solverSetup =
                mfovAsTileParameters.buildMfovAffineBlockSolverSetup(MFOVAsTileParameters.SolveType.TRANSLATION);
        solverSetup.renderWeb = cmdLineSetup.renderWeb;
        solverSetup.stack = cmdLineSetup.stack;
        solverSetup.matches = cmdLineSetup.matches;
        solverSetup.targetStack.stack = solverSetup.stack + alignedStackSuffix;
        solverSetup.targetStack.completeStack = true;

        System.out.println("running solve to create " + solverSetup.targetStack.stack);
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

        if (PRINT_PME_LINKS) {
            System.out.println("Point Match Explorer URL: " + pmeBase + pmeQuery);
        }

        return solverSetup.targetStack.stack;
    }

    @SuppressWarnings("unused")
    private static void setupTestInputData()
            throws IOException {

        final RenderDataClient sourceDataClient = buildSourceProjectClient();
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_TWO_STACK, Z_VALUES,TEST_TWO_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_THREE_STACK, Z_VALUES,TEST_THREE_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_FOUR_STACK, Z_VALUES,TEST_FOUR_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_TEN_STACK, Z_VALUES,TEST_TEN_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_ALL_STACK, Z_VALUES,null);

        final RenderDataClient sourceMatchClient = buildClient(SOURCE_MATCH_COLLECTION);
        final String groupId = String.valueOf(Z_VALUES.get(0));
        final List<CanvasMatches> sourceCmList = sourceMatchClient.getMatchesWithinGroup(groupId);

        setupMatchCollection(sourceCmList, TEST_TWO_ONLY_REAL_MATCH_COLLECTION,
                             TEST_TWO_TILE_IDS, null, true, null);
        setupMatchCollection(sourceCmList, TEST_THREE_MATCH_COLLECTION,
                             TEST_THREE_TILE_IDS, null, false, null);
        setupMatchCollection(sourceCmList, TEST_THREE_ONLY_REAL_MATCH_COLLECTION,
                             TEST_THREE_TILE_IDS, null, true, null);

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

    public static void setupStack(final RenderDataClient sourceDataClient,
                                  final StackMetaData sourceStackMetaData,
                                  final String targetProject,
                                  final String targetStackName,
                                  final List<Double> zValues,
                                  final Set<String> tileIds)
            throws IOException {

        final RenderDataClient testDataClient = buildClient(targetProject);
        testDataClient.setupDerivedStack(sourceStackMetaData, targetStackName);

        final StackId sourceStackId = sourceStackMetaData.getStackId();
        for (final Double z : zValues) {
            final ResolvedTileSpecCollection resolvedTiles = sourceDataClient.getResolvedTiles(sourceStackId.getStack(), z);
            if (tileIds != null) {
                resolvedTiles.retainTileSpecs(tileIds);
            }
            testDataClient.saveResolvedTiles(resolvedTiles, targetStackName, z);
        }
        testDataClient.setStackState(targetStackName, StackMetaData.StackState.COMPLETE);
    }

    public static void setupMatchCollection(final List<CanvasMatches> sourceCanvasMatchesList,
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

    public static RenderDataClient buildSourceProjectClient() {
        return new RenderDataClient(BASE_DATA_URL, OWNER, SOURCE_PROJECT);
    }

    public static RenderDataClient buildClient(final String projectOrCollection) {
        return new RenderDataClient(BASE_DATA_URL, OWNER, projectOrCollection);
    }
}
