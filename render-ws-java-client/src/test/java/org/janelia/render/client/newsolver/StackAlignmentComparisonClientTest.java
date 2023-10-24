package org.janelia.render.client.newsolver;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests the {@link StackAlignmentComparisonClient} class.
 *
 * @author Michael Innerberger
 */
@SuppressWarnings("SameParameterValue")
public class StackAlignmentComparisonClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new StackAlignmentComparisonClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.
    //
    // To see old ad-hoc tests that visualized matches in ImageJ, look at code before the commit for this line.

    public static void main(final String[] args) throws Exception {
        final String fileName1 = "errors1.json.gz";
        final String fileName2 = "errors2.json.gz";
        final String stack1 = "c000_s095_v01_align2";
        final String stack2 = "c000_s095_v01_align_test_xy_ad";

        final String[] errorArgs = new String[] {
                        "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                        "--owner", "hess_wafer_53",
                        "--project", "cut_000_to_009",
                        "--matchCollection", "c000_s095_v01_match_agg2",
                        "--stack", null,
                        "--fileName", null};

        computeErrorsUsing(errorArgs, stack1, fileName1);
        computeErrorsUsing(errorArgs, stack2, fileName2);

        final String[] comparisonArgs = new String[] {
                        "--baselineFile", fileName1,
                        "--otherFile", fileName2,
                        "--metric", "ABSOLUTE_CHANGE",
                        "--reportWorstPairs", "50"};

        final StackAlignmentComparisonClient.Parameters params = new StackAlignmentComparisonClient.Parameters();
        params.parse(comparisonArgs);
        new StackAlignmentComparisonClient(params).compareErrors();
    }

    private static void computeErrorsUsing(final String[] args, final String stack, final String fileName) {
        args[9] = stack;
        args[11] = fileName;

        final StackAlignmentErrorClient.Parameters params = new StackAlignmentErrorClient.Parameters();
        params.parse(args);
        try {
            new StackAlignmentErrorClient(params).fetchAndComputeError();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
