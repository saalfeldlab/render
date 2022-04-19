package org.janelia.render.client;

import java.util.Collections;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.match.RenderableCanvasIdPairs;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Test;

/**
 * Tests the {@link ExponentialRecoveryOffsetTransformDerivationClient} class.
 *
 * @author Eric Trautman
 */
public class ExponentialRecoveryOffsetTransformDerivationClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ExponentialRecoveryOffsetTransformDerivationClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) throws Exception {

        @SuppressWarnings("HttpUrlsUsage")
        final String baseDataUrlString = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        final String owner = "Z0720_07m_VNC";
        final String project = "Sec19";
        final String stack = "v1_acquire";
        final String generalTemplateString = baseDataUrlString + "/owner/" + owner + "/project/" + project +
                                             "/stack/" + stack + "/tile/{id}/render-parameters";

        final CanvasId pCanvasId = new CanvasId("8783.0",
                                                "21-09-24_002136_0-0-0.8783.0",
                                                MontageRelativePosition.LEFT);
        final CanvasId qCanvasId = new CanvasId("8783.0",
                                                "21-09-24_002136_0-0-1.8783.0",
                                                MontageRelativePosition.RIGHT);
        final OrderedCanvasIdPair pair = new OrderedCanvasIdPair(pCanvasId, qCanvasId, 0.0);

        final RenderableCanvasIdPairs renderableCanvasIdPairs =
                new RenderableCanvasIdPairs(generalTemplateString,
                                            Collections.singletonList(pair));

        final String fitResultsPath = "/Users/trautmane/Desktop/test-fit";
        final String pairJsonPath = fitResultsPath + "/pairs.json";
        FileUtil.saveJsonFile(pairJsonPath, renderableCanvasIdPairs);

        @SuppressWarnings("HttpUrlsUsage")
        final String[] effectiveArgs = {
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "Z0720_07m_VNC",
                "--project", "Sec19",
                "--stack", "v1_acquire",
                "--targetStack", "v1_acquire_fit",
                "--problemIdPatternString", ".*_0-0-0\\..*",
                "--renderScale", "0.25",
                "--renderWithoutMask", "false",
                "--clipWidth", "500",
                "--clipHeight", "500",
                "--ccFullScaleSampleSize", "250",
                "--ccFullScaleStepSize", "5",
                "--ccMinResultThreshold", "0.5",
                "--ccCheckPeaks", "50",
                "--ccSubpixelAccuracy", "true",
                "--fitResultsDir", fitResultsPath,
                "--pairJson", pairJsonPath,
                "--completeTargetStack",
                };

        ExponentialRecoveryOffsetTransformDerivationClient.main(effectiveArgs);
    }


}
