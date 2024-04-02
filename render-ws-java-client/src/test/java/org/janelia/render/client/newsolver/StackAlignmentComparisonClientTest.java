package org.janelia.render.client.newsolver;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

import java.io.IOException;

import mpicbg.models.NoninvertibleModelException;

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

    public static void main(final String[] args) throws Exception {
        final String fileName1 = "errors1.json.gz";
        final String fileName2 = "errors2.json.gz";
        final String stack1 = "c009_s310_v01_mfov_08";
        final String stack2 = "c009_s310_v01_mfov_08_exact";

        final String[] errorArgs = new String[] {
                "--baseDataUrl", "http://renderer.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
                "--matchCollection", "c009_s310_v01_match",
                "--stack", null,
                "--fileName", null};

        computeErrorsUsing(errorArgs, stack1, fileName1);
        computeErrorsUsing(errorArgs, stack2, fileName2);

        final String[] comparisonArgs = new String[] {
                "--baseDataUrl", "http://renderer.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
                "--stack", "c009_s310_v01_align_test_full",
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
        } catch (final IOException | NoninvertibleModelException e) {
            throw new RuntimeException(e);
        }
    }
}
