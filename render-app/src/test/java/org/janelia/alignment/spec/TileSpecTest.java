/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.spec;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

import org.janelia.alignment.ImageAndMask;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link org.janelia.alignment.spec.TileSpec} class.
 *
 * @author Eric Trautman
 */
public class TileSpecTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final TileSpec tileSpec = TileSpec.fromJson(JSON_WITH_UNSORTED_MIPMAP_LEVELS);

        Assert.assertNotNull("json parse returned null spec", tileSpec);
        Assert.assertEquals("invalid tileId parsed", EXPECTED_TILE_ID, tileSpec.getTileId());
        Assert.assertEquals("invalid width parsed", EXPECTED_WIDTH, tileSpec.getWidth());

        final Map.Entry<Integer, ImageAndMask> firstMipMap = tileSpec.getFirstMipmapEntry();
        Assert.assertNotNull("first mipmap entry is null", firstMipMap);
        Assert.assertEquals("mipmap sorting failed, unexpected first entry returned",
                            new Integer(0), firstMipMap.getKey());

        final ChannelSpec channelSpec = tileSpec.getAllChannels().get(0);

        Map.Entry<Integer, ImageAndMask> floorMipMap = channelSpec.getFloorMipmapEntry(3);
        Assert.assertNotNull("floor 3 mipmap entry is null", floorMipMap);
        Assert.assertEquals("invalid key for floor 3 mipmap entry",
                            new Integer(3), floorMipMap.getKey());

        floorMipMap = channelSpec.getFloorMipmapEntry(4);
        Assert.assertNotNull("floor 4 mipmap entry is null", floorMipMap);
        Assert.assertEquals("invalid key for floor 3 mipmap entry",
                            new Integer(3), floorMipMap.getKey());
    }

    @Test
    public void testCoordinateTransformsWithAffineOnly() throws Exception {
        final byte[] jsonBytes = Files.readAllBytes(Paths.get("src/test/resources/tile-test/tile_with_only_affine_transforms.json"));
        final String json = new String(jsonBytes);
        final TileSpec tileSpec = TileSpec.fromJson(json);
        final Double expectedZ = tileSpec.getZ();

        final double localX = 30;
        final double localY = 40;
        final double[] worldCoordinates = tileSpec.getWorldCoordinates(localX, localY);

        Assert.assertNotNull("worldCoordinates are null", worldCoordinates);
        Assert.assertEquals("incorrect length for worldCoordinates", 3, worldCoordinates.length);
        Assert.assertEquals("incorrect z for worldCoordinates", expectedZ, worldCoordinates[2], MAX_DOUBLE_DELTA);

        final double[] localCoordinates = tileSpec.getLocalCoordinates(
                worldCoordinates[0],
                worldCoordinates[1],
                tileSpec.getMeshCellSize());

        Assert.assertNotNull("localCoordinates are null", localCoordinates);
        Assert.assertEquals("incorrect length for localCoordinates", 3, localCoordinates.length);
        Assert.assertEquals("incorrect z for localCoordinates", expectedZ, localCoordinates[2], MAX_DOUBLE_DELTA);

        Assert.assertEquals("incorrect x for localCoordinates", localX, localCoordinates[0], MAX_DOUBLE_DELTA);
        Assert.assertEquals("incorrect y for localCoordinates", localY, localCoordinates[1], MAX_DOUBLE_DELTA);
    }

    @Test
    public void testCoordinateTransformsWithNonInvertible() throws Exception {
        final byte[] jsonBytes = Files.readAllBytes(Paths.get("src/test/resources/tile-test/tile_with_non_invertible_transforms.json"));
        final String json = new String(jsonBytes);
        final TileSpec tileSpec = TileSpec.fromJson(json);
        final Double expectedZ = tileSpec.getZ();

        final double localX = 30;
        final double localY = 40;
        final double[] worldCoordinates = tileSpec.getWorldCoordinates(localX, localY);

        Assert.assertNotNull("worldCoordinates are null", worldCoordinates);
        Assert.assertEquals("incorrect length for worldCoordinates", 3, worldCoordinates.length);
        Assert.assertEquals("incorrect z for worldCoordinates", expectedZ, worldCoordinates[2], MAX_DOUBLE_DELTA);

        final double[] localCoordinates = tileSpec.getLocalCoordinates(
                worldCoordinates[0],
                worldCoordinates[1],
                tileSpec.getMeshCellSize());

        Assert.assertNotNull("localCoordinates are null", localCoordinates);
        Assert.assertEquals("incorrect length for localCoordinates", 3, localCoordinates.length);
        Assert.assertEquals("incorrect z for localCoordinates", expectedZ, localCoordinates[2], MAX_DOUBLE_DELTA);

        Assert.assertEquals("incorrect x for localCoordinates", localX, localCoordinates[0], MAX_DOUBLE_DELTA);
        Assert.assertEquals("incorrect y for localCoordinates", localY, localCoordinates[1], MAX_DOUBLE_DELTA);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithMissingMipmaps() throws Exception {

        final TileSpec tileSpec = TileSpec.fromJson(JSON_WITH_MISSING_MIPMAP_LEVELS);

        Assert.assertNotNull("json parse returned null spec", tileSpec);
        Assert.assertEquals("invalid width parsed", EXPECTED_WIDTH, tileSpec.getWidth());

        tileSpec.validate();
    }

    @Test
    public void testGetFirstChannel() throws Exception {

        final TileSpec tileSpec = TileSpec.fromJson(JSON_WITH_UNSORTED_MIPMAP_LEVELS);
        Assert.assertNull("incorrect first channel name with no channels", tileSpec.getFirstChannelName());

        final String firstName = "DAPI";
        tileSpec.convertLegacyToChannel(firstName);
        Assert.assertEquals("incorrect first channel name with 1 channel", firstName, tileSpec.getFirstChannelName());

        tileSpec.addChannel(new ChannelSpec("TdTomato", 0.0, 0.0, new TreeMap<>(), null));
        tileSpec.addChannel(new ChannelSpec("ACQTdtomato", 0.0, 0.0, new TreeMap<>(), null));
        Assert.assertEquals("incorrect first channel name with 3 channels", firstName, tileSpec.getFirstChannelName());
    }

    @Test
    public void testDeriveBoundingBox() throws Exception {
        final byte[] jsonBytes = Files.readAllBytes(Paths.get("src/test/resources/tile-test/tile_with_only_affine_transforms.json"));
        final String json = new String(jsonBytes);
        final TileSpec tileSpec = TileSpec.fromJson(json);

        final double minX = 1108.0;
        final double minY = 1957.0;
        final double maxX = 3774.0;
        final double maxY = 4265.0;

        // mesh
        tileSpec.deriveBoundingBox(64, true, false);

        Assert.assertEquals("incorrect minX", minX, tileSpec.getMinX(), MAX_DOUBLE_DELTA);
        Assert.assertEquals("incorrect minY", minY, tileSpec.getMinY(), MAX_DOUBLE_DELTA);
        final double hackedDeltaUntilMPICBGLibIsFixed = 1.0;
        Assert.assertEquals("incorrect maxX", maxX, tileSpec.getMaxX(), hackedDeltaUntilMPICBGLibIsFixed);
        Assert.assertEquals("incorrect maxY", maxY, tileSpec.getMaxY(), hackedDeltaUntilMPICBGLibIsFixed);

        // sloppy
        tileSpec.deriveBoundingBox(64, true, true);

        Assert.assertEquals("incorrect minX", minX, tileSpec.getMinX(), MAX_DOUBLE_DELTA);
        Assert.assertEquals("incorrect minY", minY, tileSpec.getMinY(), MAX_DOUBLE_DELTA);
        Assert.assertEquals("incorrect maxX", maxX, tileSpec.getMaxX(), MAX_DOUBLE_DELTA);
        Assert.assertEquals("incorrect maxY", maxY, tileSpec.getMaxY(), MAX_DOUBLE_DELTA);

        final int iterations = 100;
        final long sloppyTime = getDerivationTime(tileSpec, true, iterations);
        final long meshTime = getDerivationTime(tileSpec, false, iterations);
        Assert.assertTrue("sloppy derivation is not faster than mesh derivation", (sloppyTime < meshTime));

        LOG.info("testDeriveBoundingBox: {} iterations, sloppy time: {}ms, mesh time: {}ms",
                 iterations, sloppyTime, meshTime);
    }

    private long getDerivationTime(final TileSpec tileSpec,
                                   final boolean sloppy,
                                   final int iterations) {
        final long start = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            tileSpec.deriveBoundingBox(64, true, sloppy);
        }
        return System.currentTimeMillis() - start;
    }

    private static final Logger LOG = LoggerFactory.getLogger(TileSpecTest.class);

    private static final String EXPECTED_TILE_ID = "test-tile-id";
    private static final int EXPECTED_WIDTH = 99;
    private static final double MAX_DOUBLE_DELTA = 0.1;

    private static final String JSON_WITH_UNSORTED_MIPMAP_LEVELS =
            "{\n" +
            "  \"tileId\": \"" + EXPECTED_TILE_ID + "\",\n" +
            "  \"width\": " + EXPECTED_WIDTH + ",\n" +
            "  \"height\": -1,\n" +
            "  \"minIntensity\": 0.0,\n" +
            "  \"maxIntensity\": 255.0,\n" +
            "  \"mipmapLevels\": {\n" +
            "    \"2\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level2.png\"\n" +
            "    },\n" +
            "    \"0\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level0.png\"\n" +
            "    },\n" +
            "    \"1\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level1.png\"\n" +
            "    },\n" +
            "    \"3\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level3.png\"\n" +
            "    }\n" +
            "  },\n" +
            "  \"transforms\": {\n" +
            "    \"type\": \"list\",\n" +
            "    \"specList\": [\n" +
            "      {\n" +
            "        \"metaData\": { \"labels\": [\"lens\"] },\n" +
            "        \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "        \"dataString\": \"1 0 0 1 0 0\"\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";

    private static final String JSON_WITH_MISSING_MIPMAP_LEVELS =
            "{\n" +
            "  \"width\": " + EXPECTED_WIDTH + ",\n" +
            "  \"height\": -1,\n" +
            "  \"minIntensity\": 0.0,\n" +
            "  \"maxIntensity\": 255.0,\n" +
            "  \"transforms\": {\n" +
            "    \"type\": \"list\",\n" +
            "    \"specList\": [\n" +
            "      {\n" +
            "        \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "        \"dataString\": \"1 0 0 1 0 0\"\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";
}
