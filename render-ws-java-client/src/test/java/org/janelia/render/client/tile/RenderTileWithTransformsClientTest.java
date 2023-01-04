package org.janelia.render.client.tile;

import ij.ImageJ;
import ij.ImagePlus;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.imglib.multithreading.SimpleMultiThreading;
import mpicbg.trakem2.transform.TransformMeshMappingWithMasks;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MontageRelativePosition;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.transform.SEMDistortionTransformA;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.janelia.render.client.parameter.RenderWebServiceParameters;
import org.junit.Test;

/**
 * Tests the {@link RenderTileWithTransformsClient} class.
 *
 * @author Eric Trautman
 */
public class RenderTileWithTransformsClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new RenderTileWithTransformsClient.Parameters());
    }

    // --------------------------------------------------------------
    // The following methods support ad-hoc interactive testing with external render web services.
    // Consequently, they aren't included in the unit test suite.

    public static void main(final String[] args) {
        try {
//            final String[] testArgs = {
//                    "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
//                    "--owner", "reiser",
//                    "--project", "Z0422_05_Ocellar",
//                    "--stack", "v3_acquire",
//                    "--rootDirectory", "/Users/trautmane/Desktop/fibsem_scan_correction",
//                    "--tileId", "22-06-17_080526_0-0-1.1263.0"
//            };
//
//            RenderTileWithTransformsClient.main(testArgs);

            showTilesWithDifferentScanCorrections();
            
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }

    public static void showTilesWithDifferentScanCorrections()
            throws IOException {

        final RenderTileWithTransformsClient.Parameters parameters = new RenderTileWithTransformsClient.Parameters();
        parameters.renderWeb = new RenderWebServiceParameters();
        parameters.renderWeb.baseDataUrl = "http://renderer-dev.int.janelia.org:8080/render-ws/v1";
        parameters.renderWeb.owner = "reiser";
        parameters.renderWeb.project = "Z0422_05_Ocellar";
        parameters.stack = "v3_acquire";

        parameters.featureRenderClip.clipWidth = 1000;
        parameters.featureRenderClip.clipHeight = 1000;

        parameters.scale = 0.25;

        // TODO: Preibisch - add/remove tile pairs
        final OrderedCanvasIdPair[] tilePairs = new OrderedCanvasIdPair[] {
                buildPair("22-06-17_080526_0-0-0.1263.0",       "22-06-17_080526_0-0-1.1263.0"),
                buildPair("22-06-17_081143_0-0-0.1264.0",       "22-06-17_081143_0-0-1.1264.0"),
//                buildPair("22-06-18_034043_0-0-0.2097.0",       "22-06-18_034043_0-0-1.2097.0"),
//                buildPair("22-06-18_125654_0-0-0.patch.2098.0", "22-06-18_125654_0-0-1.patch.2098.0") // (patched, so really from z 2099)
        };

        // TODO: Preibisch - add/change scan correction parameters as needed (my test_a is just an example)
        final String[][] testSpecificArgs = {
                // test name, scan correction parameters: a * exp(-x/b) + c * exp(-x/d) , last param 0 => x dimension
                {    "original", "  19.4   64.8   24.4   972.0   0"},
                {      "test_a", "1900.4   64.8   24.4   972.0   0"},
        };

        final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);

        new ImageJ();

        for (final OrderedCanvasIdPair tilePair : tilePairs) {
            final CanvasId p = tilePair.getP();
            final CanvasId q = tilePair.getQ();
            for (final String[] testArgs : testSpecificArgs) {
                final LeafTransformSpec transformSpec = new LeafTransformSpec(SEMDistortionTransformA.class.getName(),
                                                                              testArgs[1]);
                final List<TransformSpec> tileTransforms = Collections.singletonList(transformSpec);
                showTile(client, tileTransforms, parameters.scale, testArgs[0], p);
                showTile(client, tileTransforms, parameters.scale, testArgs[0], q);
            }
        }

        SimpleMultiThreading.threadHaltUnClean();
    }

    private static void showTile(final RenderTileWithTransformsClient client,
                                 final List<TransformSpec> tileTransforms,
                                 final double scale,
                                 final String testName,
                                 final CanvasId canvasId)
            throws IOException {
        final String tileId = canvasId.getId();
        final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm =
                client.renderTile(tileId, tileTransforms, scale, canvasId, null);
        new ImagePlus(testName + "::" + tileId, ipwm.ip).show();
    }

    public static OrderedCanvasIdPair buildPair(final String pId,
                                                final String qId) throws IllegalArgumentException {

        final Matcher pMatcher = TILE_ID_PATTERN.matcher(pId);
        final Matcher qMatcher = TILE_ID_PATTERN.matcher(qId);

        if (! (pMatcher.matches() && qMatcher.matches())) {
            throw new IllegalArgumentException("pId " + pId + " and qId "+ qId + " do not match FIB-SEM tile pattern");
        }

        final int pRow = Integer.parseInt(pMatcher.group(1));
        final int pColumn = Integer.parseInt(pMatcher.group(2));
        final String pGroupId = pMatcher.group(3);

        final int qRow = Integer.parseInt(qMatcher.group(1));
        final int qColumn = Integer.parseInt(qMatcher.group(2));
        final String qGroupId = qMatcher.group(3);

        if (! pGroupId.equals(qGroupId)) {
            throw new IllegalArgumentException("pId " + pId + " and qId "+ qId + " must be in same z-layer");
        }

        if ((pRow != qRow) && (pColumn != qColumn)) {
            throw new IllegalArgumentException("pId " + pId + " and qId "+ qId + " must be in same row or column");
        }

        final MontageRelativePosition pPos;
        final MontageRelativePosition qPos;
        if (pColumn < qColumn) {
            pPos = MontageRelativePosition.LEFT;
            qPos = MontageRelativePosition.RIGHT;
        } else if (qColumn < pColumn) {
            qPos = MontageRelativePosition.LEFT;
            pPos = MontageRelativePosition.RIGHT;
        } else if (pRow < qRow) {
            pPos = MontageRelativePosition.TOP;
            qPos = MontageRelativePosition.BOTTOM;
        } else { // qRow < pRow
            qPos = MontageRelativePosition.TOP;
            pPos = MontageRelativePosition.BOTTOM;
        }

        return new OrderedCanvasIdPair(new CanvasId(pGroupId, pId, pPos),
                                       new CanvasId(qGroupId, qId, qPos),
                                       0.0);
    }

    private static final Pattern TILE_ID_PATTERN = Pattern.compile(".*_0-(\\d)-(\\d)\\.(\\d++)\\.0");
}
