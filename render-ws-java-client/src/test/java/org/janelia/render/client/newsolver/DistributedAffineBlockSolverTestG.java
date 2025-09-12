package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.LogbackTestTools;
import org.janelia.render.client.RenderDataClient;

import static org.janelia.render.client.newsolver.DistributedAffineBlockSolverTest.*;

/**
 * Tests the {@link DistributedAffineBlockSolver} class.
 */
@SuppressWarnings({"SameParameterValue", "unused"})
public class DistributedAffineBlockSolverTestG {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    private static final String SOURCE_STACK = "w60_s360_r00_gc_pa_mat_render";
    private static final List<Double> Z_VALUES_1 = List.of(1.0);
    private static final List<Double> Z_VALUES_2 = List.of(2.0);
    private static final String SOURCE_MATCH_COLLECTION = "w60_s360_r00_gc_pa_mat_render_match_cmB";

    private static final boolean PRINT_PME_LINKS = false; // set to true to print Point Match Explorer links

    // NOTE: project should differ from other tests, 'test_c...' instead of 'test_...'
    private static final String TEST_PROJECT = "test_g_mfov_as_tile";

    private static final String TEST_G_Z1_STACK = "test_g_z1_mfovs";
    private static final String TEST_G_Z1_MATCH_COLLECTION = "test_g_z1_mfovs_match";

    private static final String TEST_G_Z2_STACK = "test_g_z2_mfovs";
    private static final String TEST_G_Z2_MATCH_COLLECTION = "test_g_z2_mfovs_match";

    public static void main(final String[] args) throws Exception {

        // setupTestInputData(); // TODO: uncomment to setup test input data

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        final String alignSuffixWithTime = "_align_" + sdf.format(System.currentTimeMillis());

        LogbackTestTools.setRootLogLevelToError(); // hide all logging

        debugInconsistentAlignments(TEST_PROJECT,
                                    TEST_G_Z1_STACK,
                                    Z_VALUES_1,
                                    TEST_G_Z1_MATCH_COLLECTION,
                                    new char[]{'a'},
                                    alignSuffixWithTime,
                                    false);

        debugInconsistentAlignments(TEST_PROJECT,
                                    TEST_G_Z2_STACK,
                                    Z_VALUES_2,
                                    TEST_G_Z2_MATCH_COLLECTION,
                                    new char[]{'a'},
                                    alignSuffixWithTime,
                                    false);
    }

    @SuppressWarnings("unused")
    private static void setupTestInputData()
            throws IOException {

        final RenderDataClient sourceDataClient = buildSourceProjectClient();
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_G_Z1_STACK, Z_VALUES_1, null);
        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, TEST_G_Z2_STACK, Z_VALUES_2, null);

        final RenderDataClient sourceMatchClient = buildClient(SOURCE_MATCH_COLLECTION);

        List<CanvasMatches> sourceCmList = new ArrayList<>(sourceMatchClient.getMatchesWithPGroupId(String.valueOf(1.0),
                                                                                                    false));
        setupMatchCollection(sourceCmList, TEST_G_Z1_MATCH_COLLECTION,
                             null, null, false, null);

        sourceCmList = new ArrayList<>(sourceMatchClient.getMatchesWithPGroupId(String.valueOf(2.0),
                                                                                false));
        setupMatchCollection(sourceCmList, TEST_G_Z2_MATCH_COLLECTION,
                             null, null, false, null);
    }

}
