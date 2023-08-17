package org.janelia.render.client.zspacing;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link ZPositionCorrectionClient} class.
 *
 * @author Eric Trautman
 */
public class ZPositionCorrectionClientTest {

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new ZPositionCorrectionClient.Parameters());
    }

    @Test
    public void testNormalizeTransforms() {

        final int normalizedEdgeLayerCount = 3;

        // min=4, max=16, median delta z=0.8, outliers < 0.64 or > 0.96
        final double[] transforms = {
                   4.0, 6.3, 6.8, 7.6, 8.4, 9.2, 10.0, 10.8, 11.6, 12.4, 13.0, 13.4, 16.0
//                      2.3, 0.5, 0.8, 0.8, 0.8,  0.8,  0.8,  0.8,  0.8,  0.6,  0.4,  2.6  (corrected delta z)
//                      1.0, 1.0,                                               1.0,  1.0  (reset delta z)
//                 4.0, 5.0, 6.0, 6.8, 7.6, 8.4,  9.2, 10.0, 10.8, 11.6, 12.2, 13.2, 14.2  (normalized values)
        };

        final double[] expectedTransforms = {
                4, 5.18, 6.35, 7.29, 8.23, 9.18, 10.12, 11.06, 12.0, 12.94, 13.65, 14.82, 16
//                 1.18, 1.17, 0.94, 0.94, 0.95,  0.94,  0.94,  0.94, 0.94,  0.71,  1.17,  1.18  (scaled delta z)
        };
        final double[] normalizedTransforms = ZPositionCorrectionClient.normalizeTransforms(transforms,
                                                                                            normalizedEdgeLayerCount);

        Assert.assertArrayEquals("bad scaled normalized transform ",
                                 expectedTransforms, normalizedTransforms, 0.01);
    }

    @Test
    public void testGetSortedZListForBatch() {
        final int minZ = 1;
        final int maxZ = 30;
        final int comparisonRange = 2;
        final List<Double> fullSortedZList =
                IntStream.rangeClosed(minZ, maxZ).boxed().map(Double::valueOf).collect(Collectors.toList());

        final int totalBatchCount = 4;
        final List<List<Double>> batchedLists = new ArrayList<>();
        for (int b = 1; b <= totalBatchCount; b++) {
            batchedLists.add(ZPositionCorrectionClient.getSortedZListForBatch(fullSortedZList,
                                                                              b,
                                                                              totalBatchCount,
                                                                              comparisonRange));
        }

        // given: minZ=1, maxZ=30, totalBatchCount=4
        //
        // overlaps  normal
        //            1  2  3  4  5  6  7  8
        //  7  8      9 10 11 12 13 14 15 16
        // 15 16     17 18 19 20 21 22 23
        // 22 23     24 25 26 27 28 29 30

        validateZList("for batch 1", batchedLists.get(0), 8, 1.0);
        validateZList("for batch 2", batchedLists.get(1), 10, 7.0);
        validateZList("for batch 3", batchedLists.get(2), 9, 15.0);
        validateZList("for batch 4", batchedLists.get(3), 9, 22.0);
    }

    private void validateZList(final String context,
                               final List<Double> zList,
                               final int expectedLayerCount,
                               final Double expectedFirstZ) {
        Assert.assertEquals("invalid number of layers " + context, expectedLayerCount, zList.size());
        Assert.assertEquals("invalid first z " + context, expectedFirstZ, zList.get(0));
    }

    public static void main(final String[] args) {

        final String userHome = System.getProperty("user.home");
        final String[] effectiveArgs = (args != null) && (args.length > 0) ? args : new String[]{
                "--baseDataUrl", "http://renderer-dev.int.janelia.org:8080/render-ws/v1",
                "--owner", "hess_wafer_53",
                "--project", "cut_000_to_009",
                "--stack", "c000_s095_v01_align",
                "--scale", "0.05",
                "--minZ", "1",
                "--maxZ", "2",
//                "--runName", "testWafer53",
//                "--runName", "testBatch",
//                "--correlationBatch", "1:1",
//                "--solveExisting",
                "--poorCorrelationThreshold", "0.975",
                "--poorCorrelationRegionRows", "12",
                "--poorCorrelationRegionColumns", "12",

//                "--minX", "2500",
//                "--maxX", "5500",
//                "--minY", "400",
//                "--maxY", "1700",
//                "--debugFormat", "png",
                "--optionsJson", userHome + "/Desktop/zcorr/inference-options.sf_0_1.json",
                "--rootDirectory", userHome + "/Desktop/zcorr"
        };

        ZPositionCorrectionClient.main(effectiveArgs);
    }
    
}
