package org.janelia.render.client.newsolver;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

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
        final String[] argString = new String[] {
                        "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                        "--owner", "hess_wafer_53",
                        "--project", "cut_000_to_009",
                        "--matchCollection", "c000_s095_v01_match_agg2",
                        "--baselineStack", "c000_s095_v01_align2",
                        "--otherStack", "c000_s095_v01_align_test_xy_ad",
                        "--differenceMetric", "RELATIVE",
                        "--fileName", "pairwiseErrorDifferences.json"};

        StackAlignmentComparisonClient.main(argString);
    }
}
