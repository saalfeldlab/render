package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.LogbackTestTools;
import org.janelia.render.client.RenderDataClient;

import static org.janelia.render.client.newsolver.DistributedAffineBlockSolverTest.*;

/**
 * Tests the {@link DistributedAffineBlockSolver} class.
 */
@SuppressWarnings({"SameParameterValue", "unused"})
public class DistributedAffineBlockSolverTestB {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    private static final String SOURCE_STACK = "w60_s360_r00_gc_20250815a_mat_render";
    private static final List<Double> Z_VALUES = List.of(10.0, 11.0, 12.0);
    private static final String SOURCE_MATCH_COLLECTION = "w60_s360_r00_gc_20250815a_mat_render_match";

    private static final boolean PRINT_PME_LINKS = false; // set to true to print Point Match Explorer links

    // NOTE: project should differ from other tests, 'test_b...' instead of 'test_...'
    private static final String TEST_PROJECT = "test_b_mfov_as_tile";

    private static final String TEST_THREE_STACK = "test_b_three_mfovs";
    // NOTE: match collection prefix should differ from other tests, 'test_b...' instead of 'test_...'
    private static final String TEST_THREE_MATCH_COLLECTION = "test_b_three_mfovs_match";
    private static final Set<String> TEST_THREE_TILE_IDS = Set.of("w60_s360_r00_gc_z010_m0020",
                                                                  "w60_s360_r00_gc_z011_m0020",
                                                                  "w60_s360_r00_gc_z012_m0020");

    private static final String TEST_TWELVE_STACK = "test_b_twelve_mfovs";
    private static final String TEST_TWELVE_MATCH_COLLECTION = "test_b_twelve_mfovs_match";
    private static final Set<String> TEST_TWELVE_TILE_IDS = Set.of(
            "w60_s360_r00_gc_z010_m0020", "w60_s360_r00_gc_z010_m0021", "w60_s360_r00_gc_z010_m0030", "w60_s360_r00_gc_z010_m0031",
            "w60_s360_r00_gc_z011_m0020", "w60_s360_r00_gc_z011_m0021", "w60_s360_r00_gc_z011_m0030", "w60_s360_r00_gc_z011_m0031",
            "w60_s360_r00_gc_z012_m0020", "w60_s360_r00_gc_z012_m0021", "w60_s360_r00_gc_z012_m0030", "w60_s360_r00_gc_z012_m0031");

    public static void main(final String[] args) throws Exception {

//        setupTestInputData(); // TODO: uncomment to setup test input data

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String alignSuffixWithTime = "_align_" + sdf.format(System.currentTimeMillis());

        LogbackTestTools.setRootLogLevelToError(); // hide all logging

        // TODO: uncomment to run one more of these tests
//        final String[][] debugTestArgs = {
//                { TEST_THREE_STACK, TEST_THREE_MATCH_COLLECTION, alignSuffixWithTime },
//                { TEST_TWELVE_STACK, TEST_TWELVE_MATCH_COLLECTION, alignSuffixWithTime }
//        };
//
//        for (final String[] testArgs : debugTestArgs) {
//            debugInconsistentAlignments(TEST_PROJECT,
//                                        testArgs[0],
//                                        Z_VALUES,
//                                        testArgs[1],
//                                        new char[]{'a', 'b', 'c'},
//                                        testArgs[2],
//                                        false);
//        }
    }

    @SuppressWarnings("unused")
    private static void setupTestInputData()
            throws IOException {

        final RenderDataClient sourceDataClient = buildSourceProjectClient();
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_THREE_STACK, Z_VALUES, TEST_THREE_TILE_IDS);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_TWELVE_STACK, Z_VALUES, TEST_TWELVE_TILE_IDS);

        final RenderDataClient sourceMatchClient = buildClient(SOURCE_MATCH_COLLECTION);
        final List<CanvasMatches> sourceCmList = new ArrayList<>();
        for (final Double z : Z_VALUES) {
            // pull inside and outside group matches because we are testing cross layer matches
            sourceCmList.addAll(sourceMatchClient.getMatchesWithPGroupId(String.valueOf(z), false));
        }

        setupMatchCollection(sourceCmList, TEST_THREE_MATCH_COLLECTION,
                             TEST_THREE_TILE_IDS, null, false, null);
        setupMatchCollection(sourceCmList, TEST_TWELVE_MATCH_COLLECTION,
                             TEST_TWELVE_TILE_IDS, null, false, null);
    }

}
