package org.janelia.render.client.parameter;

import java.io.StringReader;
import java.util.List;

import org.janelia.alignment.spec.TileBounds;
import org.janelia.render.client.parameter.ZDistanceParameters.DistanceListForRegion;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link ZDistanceParameters} class.
 *
 * @author Eric Trautman
 */
public class ZDistanceParametersTest {

    @Test
    public void testNormalization() {
        final List<DistanceListForRegion> listOfDistanceLists =
                DistanceListForRegion.fromJsonArray(new StringReader(Z_DISTANCE_JSON));
        final ZDistanceParameters parameters = new ZDistanceParameters(listOfDistanceLists);
        final List<DistanceListForRegion> normalizedDistances = parameters.getNormalizedDistances();

        Assert.assertEquals("invalid number of normalized distance lists",
                            2, normalizedDistances.size());

        final DistanceListForRegion defaultList = normalizedDistances.get(0);
        Assert.assertEquals("invalid maxDistance for default list " + defaultList,
                            1, defaultList.getMaxDistance());

        final List<Integer> defaultDistances = defaultList.getDistanceList();
        Assert.assertEquals("invalid number of distances for default list " + defaultList,
                            2, defaultDistances.size());
    }

    @Test
    public void testIncludePair() {
        final List<DistanceListForRegion> listOfDistanceLists =
                DistanceListForRegion.fromJsonArray(new StringReader(Z_DISTANCE_JSON));
        final ZDistanceParameters parameters = new ZDistanceParameters(listOfDistanceLists);

        final TileBounds[][] includedTilePairs = {
                { buildBounds("0-0-1", 1916.0), buildBounds("0-0-2", 1916.0) }, // zd 0
                { buildBounds("0-0-1", 1916.0), buildBounds("0-0-2", 1917.0) }, // zd 1
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1201.0) }, // zd 0
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1202.0) }, // zd 1
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1203.0) }, // zd 2
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1206.0) }, // zd 5
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1211.0) }  // zd 10
        };

        for (final TileBounds[] testPair : includedTilePairs) {
            final String pairId = "pair " + testPair[0].getTileId() + " - " + testPair[1].getTileId();
            Assert.assertTrue(pairId + " should be included",
                              parameters.includePair(testPair[0], testPair[1]));
        }

        final TileBounds[][] excludedTilePairs = {
                { buildBounds("0-0-1", 1916.0), buildBounds("0-0-2", 1918.0) }, // zd 2
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1204.0) }, // zd 3
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1205.0) }, // zd 4
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1207.0) }, // zd 6
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1208.0) }, // zd 7
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1209.0) }, // zd 8
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1210.0) }, // zd 9
                { buildBounds("0-0-1", 1201.0), buildBounds("0-0-2", 1212.0) }  // zd 11
        };

        for (final TileBounds[] testPair : excludedTilePairs) {
            final String pairId = "pair " + testPair[0].getTileId() + " - " + testPair[1].getTileId();
            Assert.assertFalse(pairId + " should NOT be included",
                               parameters.includePair(testPair[0], testPair[1]));
        }
    }

    private static TileBounds buildBounds(final String tileIdPrefix,
                                          final Double z) {
        final String tileId = tileIdPrefix + "." + z;
        return new TileBounds(tileId, z.toString(), z, 0.0, 1.0, 12.0, 13.0);
    }

    private static final String Z_DISTANCE_JSON =
            "[" +
            "  {" +
            "    \"distanceList\": [ 1 ]" +
            "  }," +
            "  {" +
            "    \"distanceList\": [ 1, 2, 5, 10 ]," +
            "    \"bounds\": { \"minZ\": 1201, \"maxZ\": 1290 }" +
            "  }" +
            "]";
}
