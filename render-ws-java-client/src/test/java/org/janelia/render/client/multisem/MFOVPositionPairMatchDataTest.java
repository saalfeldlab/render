package org.janelia.render.client.multisem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.CanvasMatches;
import org.janelia.alignment.match.Matches;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.TileSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MFOVPositionPairMatchData} class.
 */
public class MFOVPositionPairMatchDataTest {

    @Test
    public void testDeriveMatchesUsingStartPositions() {
        final String groupId = "76.0";
        final String pTileId = "w60_magc0160_scan081_m0030_r81_s55";
        final String qTileId = "w60_magc0160_scan081_m0030_r88_s84";
        final OrderedCanvasIdPair tilePair =
                new OrderedCanvasIdPair(new CanvasId(groupId, pTileId),
                                        new CanvasId(groupId, qTileId),
                                        0.0);

        final OrderedCanvasIdPair expectedPositionCanvasPair =
                new OrderedCanvasIdPair(new CanvasId("magc0160", "m0030_s55"),
                                        new CanvasId("magc0160", "m0030_s84"),
                                        0.0);

        final MFOVPositionPair positionPair = new MFOVPositionPair(tilePair);
        Assert.assertEquals("invalid P canvasId for position pair",
                            expectedPositionCanvasPair.getP(), positionPair.getP());
        Assert.assertEquals("invalid Q canvasId for position pair",
                            expectedPositionCanvasPair.getQ(), positionPair.getQ());

        final TileSpec pTileSpec = TileSpec.fromJson(
                "{\n" +
                "  \"tileId\" : \"" + pTileId + "\",\n" +
                "  \"layout\" : { \"sectionId\" : \"76.0\", \"imageRow\" : 19, \"imageCol\" : 104, \"stageX\" : 22361.073238943354, \"stageY\" : -72386.74654066144 },\n" +
                "  \"z\" : 76.0, \"minX\" : 103938.0, \"minY\" : 22345.0, \"maxX\" : 105942.0, \"maxY\" : 24093.0, \"width\" : 2000.0, \"height\" : 1748.0, \"minIntensity\" : 0.0, \"maxIntensity\" : 255.0,\n" +
                "  \"mipmapLevels\" : { \"0\" : { \"imageUrl\" : \"https://storage.googleapis.com/janelia-spark-test/hess_wafer_60_data/scan_081/slab_0160/mfov_0030/sfov_055.png\" } },\n" +
                "  \"transforms\" : {\n" +
                "    \"type\" : \"list\",\n" +
                "    \"specList\" : [ {\n" +
                "      \"type\" : \"leaf\",\n" +
                "      \"className\" : \"org.janelia.alignment.transform.ExponentialFunctionOffsetTransform\",\n" +
                "      \"dataString\" : \"3.164065083689898,0.010223592506552219,0.0,0\"\n" +
                "    }, {\n" +
                "      \"type\" : \"leaf\",\n" +
                "      \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "      \"dataString\" : \"1 0 0 1 103942.09540116317 22345.489256663568\"\n" +
                "    } ]\n" +
                "  },\n" +
                "  \"meshCellSize\" : 64.0\n" +
                "}"
        );

        final TileSpec qTileSpec = TileSpec.fromJson(
                "{\n" +
                "  \"tileId\" : \"" + qTileId + "\",\n" +
                "  \"layout\" : { \"sectionId\" : \"76.0\", \"imageRow\" : 20, \"imageCol\" : 105, \"stageX\" : 23295.766526900617, \"stageY\" : -70754.30512322378 },\n" +
                "  \"z\" : 76.0, \"minX\" : 104873.0, \"minY\" : 23977.0, \"maxX\" : 106877.0, \"maxY\" : 25725.0, \"width\" : 2000.0, \"height\" : 1748.0, \"minIntensity\" : 0.0, \"maxIntensity\" : 255.0,\n" +
                "  \"mipmapLevels\" : { \"0\" : { \"imageUrl\" : \"https://storage.googleapis.com/janelia-spark-test/hess_wafer_60_data/scan_081/slab_0160/mfov_0030/sfov_084.png\" } },\n" +
                "  \"transforms\" : {\n" +
                "    \"type\" : \"list\",\n" +
                "    \"specList\" : [ {\n" +
                "      \"type\" : \"leaf\",\n" +
                "      \"className\" : \"org.janelia.alignment.transform.ExponentialFunctionOffsetTransform\",\n" +
                "      \"dataString\" : \"3.164065083689898,0.010223592506552219,0.0,0\"\n" +
                "    }, {\n" +
                "      \"type\" : \"leaf\",\n" +
                "      \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
                "      \"dataString\" : \"1 0 0 1 104876.78868912044 23977.930674101226\"\n" +
                "    } ]\n" +
                "  },\n" +
                "  \"meshCellSize\" : 64.0\n" +
                "}"
        );

        final MFOVPositionPairMatchData positionPairMatchData = new MFOVPositionPairMatchData(positionPair);
        positionPairMatchData.addPair(tilePair, pTileSpec, qTileSpec);
        positionPairMatchData.addUnconnectedPair(tilePair);

        final double derivedMatchWeight = 0.000001;
        final List<CanvasMatches> derivedMatchesList = new ArrayList<>();
        final Set<OrderedCanvasIdPair> unconnectedPairs = new HashSet<>();
        unconnectedPairs.add(tilePair);

        try {
            positionPairMatchData.deriveMatchesUsingStartPositions(derivedMatchWeight,
                                                                   derivedMatchesList,
                                                                   unconnectedPairs);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

        Assert.assertEquals("invalid number of derived matches", 1, derivedMatchesList.size());

        final CanvasMatches derivedMatches = derivedMatchesList.get(0);
        final Matches matches = derivedMatches.getMatches();
        final double[] ws = matches.getWs();
        Assert.assertEquals("invalid number of weights", 4, ws.length);
        Assert.assertEquals("invalid weight for first point", derivedMatchWeight, ws[0], 0.0000005);

        final double[][] ps = matches.getPs();
        final double[][] qs = matches.getQs();
        for (int i = 0; i < ps[0].length; i++) {

            final double[] pWorld = {
                    pTileSpec.getMinX() + ps[0][i],
                    pTileSpec.getMinY() + ps[1][i]
            };
            final double[] qWorld = {
                    qTileSpec.getMinX() + qs[0][i],
                    qTileSpec.getMinY() + qs[1][i]
            };

            Assert.assertEquals("p and q world x for match point [" + i + "] differ",
                                pWorld[0], qWorld[0], 0.01);
            Assert.assertEquals("p and q world y for match point [" + i + "] differ",
                                pWorld[1], qWorld[1], 0.01);
        }
    }

}
