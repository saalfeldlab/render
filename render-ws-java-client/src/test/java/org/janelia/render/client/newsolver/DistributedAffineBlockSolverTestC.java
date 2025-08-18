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
public class DistributedAffineBlockSolverTestC {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    private static final String SOURCE_STACK = "w60_s360_r00_gc_20250815a_mat_render";
    private static final List<Double> Z_VALUES = List.of(16.0, 17.0, 18.0, 19.0);
    private static final String SOURCE_MATCH_COLLECTION = "w60_s360_r00_gc_20250815a_mat_render_match";

    private static final boolean PRINT_PME_LINKS = false; // set to true to print Point Match Explorer links

    // NOTE: project should differ from other tests, 'test_c...' instead of 'test_...'
    private static final String TEST_PROJECT = "test_c_mfov_as_tile";

    private static final String TEST_24_STACK = "test_c_24_mfovs";
    private static final String TEST_24_MATCH_COLLECTION = "test_c_24_mfovs_match";
    private static final Set<String> TEST_24_TILE_IDS = Set.of(
            "w60_s360_r00_gc_z016_m0005", "w60_s360_r00_gc_z016_m0006", "w60_s360_r00_gc_z016_m0009",
            "w60_s360_r00_gc_z016_m0010", "w60_s360_r00_gc_z016_m0016", "w60_s360_r00_gc_z016_m0017",
            "w60_s360_r00_gc_z017_m0005", "w60_s360_r00_gc_z017_m0006", "w60_s360_r00_gc_z017_m0009",
            "w60_s360_r00_gc_z017_m0010", "w60_s360_r00_gc_z017_m0016", "w60_s360_r00_gc_z017_m0017",
            "w60_s360_r00_gc_z018_m0005", "w60_s360_r00_gc_z018_m0006", "w60_s360_r00_gc_z018_m0009",
            "w60_s360_r00_gc_z018_m0010", "w60_s360_r00_gc_z018_m0016", "w60_s360_r00_gc_z018_m0017",
            "w60_s360_r00_gc_z019_m0005", "w60_s360_r00_gc_z019_m0006", "w60_s360_r00_gc_z019_m0009",
            "w60_s360_r00_gc_z019_m0010", "w60_s360_r00_gc_z019_m0016", "w60_s360_r00_gc_z019_m0017");

    public static void main(final String[] args) throws Exception {

//        setupTestInputData(); // TODO: uncomment to setup test input data

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String alignSuffixWithTime = "_align_" + sdf.format(System.currentTimeMillis());

        LogbackTestTools.setRootLogLevelToError(); // hide all logging

        // TODO: uncomment to run one more of these tests
//        final String[][] debugTestArgs = {
//                {TEST_24_STACK, TEST_24_MATCH_COLLECTION, alignSuffixWithTime }
//        };
//
//        for (final String[] testArgs : debugTestArgs) {
//            debugInconsistentAlignments(TEST_PROJECT,
//                                        testArgs[0],
//                                        Z_VALUES,
//                                        testArgs[1],
//                                        new char[]{'a'},
//                                        testArgs[2],
//                                        false);
//        }
    }

    @SuppressWarnings("unused")
    private static void setupTestInputData()
            throws IOException {

        final RenderDataClient sourceDataClient = buildSourceProjectClient();
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_24_STACK, Z_VALUES, TEST_24_TILE_IDS);

        final RenderDataClient sourceMatchClient = buildClient(SOURCE_MATCH_COLLECTION);
        final List<CanvasMatches> sourceCmList = new ArrayList<>();
        for (final Double z : Z_VALUES) {
            // pull inside and outside group matches because we are testing cross layer matches
            sourceCmList.addAll(sourceMatchClient.getMatchesWithPGroupId(String.valueOf(z), false));
        }

        setupMatchCollection(sourceCmList, TEST_24_MATCH_COLLECTION,
                             TEST_24_TILE_IDS, null, false, null);
    }

}
