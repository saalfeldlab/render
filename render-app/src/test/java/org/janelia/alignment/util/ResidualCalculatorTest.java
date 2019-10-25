package org.janelia.alignment.util;

import java.util.List;

import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.PointMatch;

import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.junit.Test;

/**
 * Tests the {@link ResidualCalculator} class.
 *
 * @author Eric Trautman
 */
public class ResidualCalculatorTest {

    @Test
    public void testRun()
            throws NoninvertibleModelException {

        final String pMatchJson =
                "{\n" +
                "\"tileId\": \"151217110113032085.535.0\",\n" +
                "\"z\": 535, \"minX\": 8, \"minY\": 32, \"maxX\": 2671, \"maxY\": 2322,\n" +
                "\"width\": 2560, \"height\": 2160,\n" +
                "\"transforms\": {\n" +
                "\"type\": \"list\",\n" +
                "\"specList\": []}}";

        final TileSpec pMatchTileSpec = TileSpec.fromJson(pMatchJson);

        final String pAlignedJson =
                "{\n" +
                "\"tileId\": \"151217110113032085.535.0\",\n" +
                "\"z\": 535, \"minX\": 33114, \"minY\": 32638, \"maxX\": 35784, \"maxY\": 34945,\n" +
                "\"width\": 2560, \"height\": 2160,\n" +
                "\"transforms\": {\n" +
                "\"type\": \"list\",\n" +
                "\"specList\": [\n" +
                "{\"type\": \"leaf\", \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "\"dataString\": \"-0.999639554083 -0.005504829254 0.004191949996 -1.001725487903 35784.416966470395 34978.608372966301\"\n" +
                "}]}}";

        final TileSpec pAlignedTileSpec = TileSpec.fromJson(pAlignedJson);

        final String qMatchJson =
                "{\n" +
                "\"tileId\": \"151217110113033085.535.0\",\n" +
                "\"z\": 535, \"minX\": 8, \"minY\": 32, \"maxX\": 2671, \"maxY\": 2322," +
                "\"width\": 2560, \"height\": 2160,\n" +
                "\"transforms\": {\n" +
                "\"type\": \"list\",\n" +
                "\"specList\": []}}";

        final TileSpec qMatchTileSpec = TileSpec.fromJson(qMatchJson);

        final String qAlignedJson =
                "{\n" +
                "\"tileId\": \"151217110113033085.535.0\",\n" +
                "\"z\": 535, \"minX\": 31120, \"minY\": 32599, \"maxX\": 33799, \"maxY\": 34904,\n" +
                "\"width\": 2560, \"height\": 2160,\n" +
                "\"transforms\": {\n" +
                "\"type\": \"list\",\n" +
                "\"specList\": [\n" +
                "{\"type\": \"leaf\", \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "\"dataString\": \"-1.001433887474 -0.005109778257 0.005917430007 -1.000864015786 33794.433334533365 34936.117736529850\"\n" +
                "}]}}";

        final TileSpec qAlignedTileSpec = TileSpec.fromJson(qAlignedJson);

        final String matchJson =
                "{\n" +
                "\"pGroupId\": \"535.0\",\n" +
                "\"pId\": \"151217110113032085.535.0\",\n" +
                "\"qGroupId\": \"535.0\",\n" +
                "\"qId\": \"151217110113033085.535.0\",\n" +
                "\"matches\" : {\n" +
                "    \"p\" : [ [ 2560.1941965996325, 2413.9112942072984, 2251.435464934693, 2329.8600573814765, 2211.092952452758, 2555.4460121944217, 2250.329455385571, 2398.068442078769, 2395.4977683697925, 2391.1115953227527, 2252.883919346284, 2194.5220204070743, 2563.853227802642 ], [ 565.3970791378985, 411.3584295909637, 2069.6431122079316, 1818.9130883125376, 1168.8230563077875, 476.4527422135635, 2052.155563818892, 2029.7708176014512, 1809.7939391200136, 1801.614570450891, 1801.293083285957, 1800.5741896077263, 601.4353336995042 ] ],\n" +
                "    \"q\" : [ [ 566.8473967742441, 423.377389666387, 258.00346745927595, 335.709934133493, 217.25216114449387, 562.6997540981155, 258.0194971631647, 403.7424335497306, 402.6878793541385, 398.54554557954407, 261.10426183584826, 201.15684802206715, 569.5727620093525 ], [ 533.3904989216358, 377.137526111664, 2037.5048709254368, 1785.7718930116732, 1134.763306819837, 443.1958247188568, 2019.6823761456749, 1998.8216258172304, 1778.0862520400428, 1768.9313919357605, 1769.7979329469345, 1770.1668410990153, 568.6040573691449 ] ],\n" +
                "    \"w\" : [ 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 ]\n" +
                "  }\n" +
                "}";

        final CanvasMatches canvasMatches = CanvasMatches.fromJson(matchJson);

        final StackId testStackId = new StackId("tester", "testProject", "testStack");
        final MatchCollectionId testCollectionId = new MatchCollectionId("tester", "testMatches");

        final ResidualCalculator.InputData inputData = new ResidualCalculator.InputData(pAlignedTileSpec.getTileId(),
                                                                                        qAlignedTileSpec.getTileId(),
                                                                                        testStackId,
                                                                                        testCollectionId,
                                                                                        true);

        final List<PointMatch> worldMatchList = canvasMatches.getMatches().createPointMatches();
        final List<PointMatch> localMatchList =
                ResidualCalculator.convertMatchesToLocal(worldMatchList, pMatchTileSpec, qMatchTileSpec);


        final ResidualCalculator residualCalculator = new ResidualCalculator();
        final ResidualCalculator.Result result = residualCalculator.run(testStackId,
                                                                        inputData,
                                                                        localMatchList,
                                                                        pAlignedTileSpec,
                                                                        qAlignedTileSpec);
        System.out.println(ResidualCalculator.Result.getHeader());
        System.out.println(result.toString());
    }

}
