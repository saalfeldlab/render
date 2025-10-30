package org.janelia.render.client.newsolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.LogbackTestTools;
import org.janelia.render.client.RenderDataClient;

import static org.janelia.render.client.newsolver.DistributedAffineBlockSolverTest.*;

/**
 * Tests solver issues with wafer 61 serial slab 109.
 */
@SuppressWarnings({"SameParameterValue", "unused"})
public class DistributedAffineBlockSolverTestWafer61Slab109 {

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    private static final String SOURCE_PROJECT = "w61_serial_100_to_109";
    private static final String SOURCE_STACK = "w61_s109_r00_gc_par";
    private static final String SOURCE_MATCH_COLLECTION = "w61_s109_r00_gc_par_match";

    // NOTE: project should differ from other tests, 'test_c...' instead of 'test_b...'
    private static final String TEST_PROJECT = "test_w61_s109";
    private static final List<Double> PROBLEM_A_Z_VALUES = List.of(12.0);

    @SuppressWarnings("CommentedOutCode")
    public static void main(final String[] args) throws Exception {
        //debugProblemAWithOriginalSetup();
        //debugProblemAWithAgg0p4Setup();
        //debugProblemAWithAgg0p6Setup();
    }

    private static void debugProblemAWithOriginalSetup() throws Exception {
        final String stackName = "w61_s109_problem_a_original_sgl0p2"; // note: keep w61_s109 in name for matchCollection
        final String matchCollection = stackName + "_match";

        setupProblemA(stackName, matchCollection, null);
        debugProblemA(stackName, PROBLEM_A_Z_VALUES, matchCollection);
    }

    private static void debugProblemAWithAgg0p4Setup() throws Exception {
        final String stackName = "w61_s109_problem_a_test_a_agg0p4"; // note: keep w61_s109 in name for matchCollection
        final String matchCollection = stackName + "_match";

        setupProblemA(stackName, matchCollection, "0.4");
        // TODO: hack matches, then rerun with setup commented out and debug (solve) uncommented
        // debugProblemA(stackName, PROBLEM_A_Z_VALUES, matchCollection);
    }

    private static void debugProblemAWithAgg0p6Setup() throws Exception {
        final String stackName = "w61_s109_problem_a_test_a_agg0p6"; // note: keep w61_s109 in name for matchCollection
        final String matchCollection = stackName + "_match";

        setupProblemA(stackName, matchCollection, "0.6");
        // TODO: hack matches, then rerun with setup commented out and debug (solve) uncommented
        // debugProblemA(stackName, PROBLEM_A_Z_VALUES, matchCollection);
    }

    private static void setupProblemA(final String stackName,
                                      final String matchCollection,
                                      final String trialRenderScale) throws Exception {

        final List<Double> zValues = List.of(12.0);
        final String groupId = "12.0";
        final Set<String> tileIds = Set.of(
                "w61_magc0145_scan015_m0009_r70_s90",
                "w61_magc0145_scan015_m0015_r14_s74"
        );

        setupTestInputData(stackName, zValues, tileIds, matchCollection);

        if (trialRenderScale != null) {
            System.out.println("To hack matches, use:\n");

            final String mtUrl =
                    "http://" + RENDER_DATA_HOST + ":8080/render-ws/view/match-trial.html?" +
                    "matchTrialId=TBD&matchFilter=AGGREGATED_CONSENSUS_SETS&steps=5&matchMaxTrust=4&" +
                    "minScale=0.25&matchModelType=TRANSLATION&pGroupId=" + groupId + "&qGroupId=" + groupId +
                    "&saveToCollection=" + matchCollection;
            System.out.println(mtUrl);

            System.out.println("\nwith render parameter URLs:\n");
            final String rpUrlStart = BASE_DATA_URL + "/owner/" + OWNER + "/project/" + TEST_PROJECT +
                                      "/stack/" + stackName + "/tile/";
            final String rpUrlEnd = "/render-parameters?excludeMask=false&normalizeForMatching=true&scale=0.4";
            for (final String tileId : tileIds) {
                System.out.println(rpUrlStart + tileId + rpUrlEnd + "\n");
            }
        }

    }

    private static void debugProblemA(final String stackName,
                                      final List<Double> zValues,
                                      final String matchCollection) throws Exception {
        LogbackTestTools.setRootLogLevelToError(); // hide all logging
        debugInconsistentAlignments(TEST_PROJECT,
                                    stackName,
                                    zValues,
                                    matchCollection,
                                    new char[]{'a'},
                                    "_align_",
                                    false);
    }

    @SuppressWarnings("unused")
    private static void setupTestInputData(final String stackName,
                                           final List<Double> zValues,
                                           final Set<String> tileIds,
                                           final String matchCollection)
            throws IOException {

        final RenderDataClient sourceDataClient = buildClient(SOURCE_PROJECT);
        final StackMetaData sourceStackMetaData = sourceDataClient.getStackMetaData(SOURCE_STACK);

        setupStack(sourceDataClient, sourceStackMetaData, TEST_PROJECT, stackName, zValues, tileIds);

        final RenderDataClient sourceMatchClient = buildClient(SOURCE_MATCH_COLLECTION);
        final List<CanvasMatches> sourceCmList = new ArrayList<>();
        for (final Double z : zValues) {
            // pull inside group matches because we are only testing same layer matches
            sourceCmList.addAll(sourceMatchClient.getMatchesWithinGroup(String.valueOf(z), false));
        }

        setupMatchCollection(sourceCmList, matchCollection, tileIds, null, false, null);
    }

}
