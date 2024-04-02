package org.janelia.render.client.newsolver;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests the {@link StackAlignmentErrorClient} class.
 *
 * @author Michael Innerberger
 */
@SuppressWarnings("SameParameterValue")
public class StackAlignmentErrorClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new StackAlignmentErrorClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) throws Exception {
        final String[] singleStackArgs = new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
                "--matchCollection", "c009_s310_v01_match",
                "--stack", "c009_s310_v01_mfov_08",
                "--errorMetric", "RMSE",
                "--reportWorstPairs", "20"};

        showErrorsFor(singleStackArgs);

        final String[] multiStackArgs = new String[] {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
                "--matchCollection", "c009_s310_v01_match",
                "--stack", "c009_s310_v01_mfov_08",
                "--compareTo", "c009_s310_v01_mfov_08_exact",
                "--errorMetric", "GLOBAL_LOCAL_DIFFERENCE",
                "--comparisonMetric", "ABSOLUTE_CHANGE",
                "--reportWorstPairs", "20"};

        showErrorsFor(multiStackArgs);
    }

    private static void showErrorsFor(final String[] args) throws IOException {
        StackAlignmentErrorClient.main(args);
    }
}
