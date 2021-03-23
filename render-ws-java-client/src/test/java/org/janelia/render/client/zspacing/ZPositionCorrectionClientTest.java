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
    public void testGetSortedZListForBatch() {
        final int minZ = 1;
        final int maxZ = 30;
        final int comparisonRange = 2;
        final List<Double> fullSortedZList =
                IntStream.rangeClosed(minZ, maxZ).boxed().map(Double::new).collect(Collectors.toList());

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
                "--owner", "Z0720_07m_BR",
                "--project", "Sec39",
                "--stack", "v1_acquire_trimmed_sp1",
                "--scale", "0.0625",
                "--minZ", "19950",
                "--maxZ", "19999",
                "--runName", "testFullBatch",
//                "--runName", "testBatch",
//                "--correlationBatch", "1:3",
//                "--solveExisting",

//                "--minX", "2500",
//                "--maxX", "5500",
//                "--minY", "400",
//                "--maxY", "1700",
//                "--debugFormat", "png",
//                "--optionsJson", userHome + "/Desktop/inference-options.json",
                "--rootDirectory", userHome + "/Desktop/zcorr"
        };

        ZPositionCorrectionClient.main(effectiveArgs);
    }
    
}
