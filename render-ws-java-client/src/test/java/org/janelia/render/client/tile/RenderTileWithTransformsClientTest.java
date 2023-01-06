package org.janelia.render.client.tile;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
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

        parameters.featureRenderClip.clipWidth = 1000;  // full scale clip pixels
        parameters.featureRenderClip.clipHeight = 1000; // full scale clip pixels

        parameters.scale = 0.25;

        // http://renderer.int.janelia.org:8080/ng/#!%7B%22dimensions%22:%7B%22x%22:%5B8e-9%2C%22m%22%5D%2C%22y%22:%5B8e-9%2C%22m%22%5D%2C%22z%22:%5B8e-9%2C%22m%22%5D%7D%2C%22position%22:%5B-163.37060546875%2C-4313.3564453125%2C1263.5%5D%2C%22crossSectionScale%22:2%2C%22projectionScale%22:32768%2C%22layers%22:%5B%7B%22type%22:%22image%22%2C%22source%22:%7B%22url%22:%22render://http://renderer.int.janelia.org:8080/reiser/Z0422_05_Ocellar/v3_acquire_align%22%2C%22subsources%22:%7B%22default%22:true%2C%22bounds%22:true%7D%2C%22enableDefaultSubsources%22:false%7D%2C%22tab%22:%22source%22%2C%22name%22:%22v3_acquire_align%22%7D%5D%2C%22selectedLayer%22:%7B%22layer%22:%22v3_acquire_align%22%7D%2C%22layout%22:%22xy%22%7D
        // TODO: Preibisch - add/remove tile pairs
        final OrderedCanvasIdPair[] tilePairs = new OrderedCanvasIdPair[] {
        		// TODO: sorry, 0-0-0.1263.0 <> 0-0-0.1263.0 with overlap of ~1000 px
                buildPair("22-06-17_080526_0-0-0.1263.0",       "22-06-17_080526_0-0-1.1263.0"),
                buildPair("22-06-17_081143_0-0-0.1264.0",       "22-06-17_081143_0-0-1.1264.0"),
//                buildPair("22-06-18_034043_0-0-0.2097.0",       "22-06-18_034043_0-0-1.2097.0"),
//                buildPair("22-06-18_125654_0-0-0.patch.2098.0", "22-06-18_125654_0-0-1.patch.2098.0") // (patched, so really from z 2099)
        };

        // TODO: Preibisch - add/change scan correction parameters as needed (my test_a is just an example)
        final String[][] testSpecificArgs = {
                // test name, scan correction parameters: a * exp(-x/b) + c * exp(-x/d) , last param 0 => x dimension
                {    "original", "  19.4   64.8   24.4   972.0   0"},
                {      "test_a", "  25.4   70.8   30.4   980.0   0"},
        };

        final RenderTileWithTransformsClient client = new RenderTileWithTransformsClient(parameters);

        // TODO: Preibisch - change this to your Fiji plugins directory so that stitching plugin is available
        System.getProperties().setProperty("plugins.dir", "/Applications/Fiji.app/plugins");
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
        final ImageProcessor croppedTile = quickCropMaskedArea(ipwm);
        new ImagePlus(testName + "__" + tileId, croppedTile).show();
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

    /**
     * @return image processor with masked area cropped away
     *         ("quick" hack looks for first unmasked pixel and crops rectangle from there)
     */
    public static ImageProcessor quickCropMaskedArea(final TransformMeshMappingWithMasks.ImageProcessorWithMasks ipwm) {

        final ImageProcessor croppedTile;

        if (ipwm.mask != null) {

            Integer cropX = null;
            int cropY = 0;

            // find first non-zero intensity pixel and crop from there
            for (int y = 0; y < ipwm.getHeight(); y++) {
                for (int x = 0; x < ipwm.getWidth(); x++) {
                    final int i = ipwm.mask.get(x, y);
                    if ((i == 255) && (cropX == null)) { // let's look for the first unmasked, non-interpolated pixel
                        cropX = x;
                        cropY = y;
                        break;
                    }
                }
            }

            if (cropX == null) {
                cropX = 0;
            }

            final int cropWidth = ipwm.getWidth() - cropX;
            final int cropHeight = ipwm.getHeight() - cropY;

            final Rectangle roi = new Rectangle(cropX, cropY, cropWidth, cropHeight);
            ipwm.ip.setRoi(roi);
            croppedTile = ipwm.ip.crop();

        } else {
            croppedTile = ipwm.ip;
        }

        return croppedTile;
    }

    private static final Pattern TILE_ID_PATTERN = Pattern.compile(".*_0-(\\d)-(\\d)\\.(?:patch\\.)?(\\d++)\\.0");
}
