package org.janelia.alignment.spec;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.filter.FilterSpec;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link org.janelia.alignment.spec.TileSpec} class.
 *
 * @author Eric Trautman
 */
public class TileSpecTest {

    @Test
    public void testJsonProcessing() {

        final TileSpec tileSpec = TileSpec.fromJson(JSON_WITH_UNSORTED_MIPMAP_LEVELS);

        assertNotNull(tileSpec, "json parse returned null spec");
        assertEquals(EXPECTED_TILE_ID, tileSpec.getTileId(), "invalid tileId parsed");
        assertEquals(EXPECTED_WIDTH, tileSpec.getWidth(), "invalid width parsed");

        assertTrue(tileSpec.hasLabel(EXPECTED_LABEL_A), "missing label " + EXPECTED_LABEL_A);
        assertTrue(tileSpec.hasLabel(EXPECTED_LABEL_B), "missing label " + EXPECTED_LABEL_B);

        final Map.Entry<Integer, ImageAndMask> firstMipMap = tileSpec.getFirstMipmapEntry();
        assertNotNull(firstMipMap, "first mipmap entry is null");
        assertEquals(new Integer(0), firstMipMap.getKey(),
                     "mipmap sorting failed, unexpected first entry returned");

        final ChannelSpec channelSpec = tileSpec.getAllChannels().getFirst();

        Map.Entry<Integer, ImageAndMask> floorMipMap = channelSpec.getFloorMipmapEntry(3);
        assertNotNull(floorMipMap, "floor 3 mipmap entry is null");
        assertEquals(new Integer(3), floorMipMap.getKey(),
                     "invalid key for floor 3 mipmap entry");

        floorMipMap = channelSpec.getFloorMipmapEntry(4);
        assertNotNull(floorMipMap, "floor 4 mipmap entry is null");
        assertEquals(new Integer(3), floorMipMap.getKey(),
                     "invalid key for floor 3 mipmap entry");

        final FilterSpec filterSpec = channelSpec.getFilterSpec();
        assertNotNull(filterSpec, "filterSpec is null");
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

        assertNotNull(worldCoordinates, "worldCoordinates are null");
        assertEquals(3, worldCoordinates.length, "incorrect length for worldCoordinates");
        assertEquals(expectedZ, worldCoordinates[2], MAX_DOUBLE_DELTA, "incorrect z for worldCoordinates");

        final double[] localCoordinates = tileSpec.getLocalCoordinates(
                worldCoordinates[0],
                worldCoordinates[1],
                tileSpec.getMeshCellSize());

        assertNotNull(localCoordinates, "localCoordinates are null");
        assertEquals(3, localCoordinates.length, "incorrect length for localCoordinates");
        assertEquals(expectedZ, localCoordinates[2], MAX_DOUBLE_DELTA, "incorrect z for localCoordinates");

        assertEquals(localX, localCoordinates[0], MAX_DOUBLE_DELTA, "incorrect x for localCoordinates");
        assertEquals(localY, localCoordinates[1], MAX_DOUBLE_DELTA, "incorrect y for localCoordinates");
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

        assertNotNull(worldCoordinates, "worldCoordinates are null");
        assertEquals(3, worldCoordinates.length, "incorrect length for worldCoordinates");
        assertEquals(expectedZ, worldCoordinates[2], MAX_DOUBLE_DELTA, "incorrect z for worldCoordinates");

        final double[] localCoordinates = tileSpec.getLocalCoordinates(
                worldCoordinates[0],
                worldCoordinates[1],
                tileSpec.getMeshCellSize());

        assertNotNull(localCoordinates, "localCoordinates are null");
        assertEquals(3, localCoordinates.length, "incorrect length for localCoordinates");
        assertEquals(expectedZ, localCoordinates[2], MAX_DOUBLE_DELTA, "incorrect z for localCoordinates");

        assertEquals(localX, localCoordinates[0], MAX_DOUBLE_DELTA, "incorrect x for localCoordinates");
        assertEquals(localY, localCoordinates[1], MAX_DOUBLE_DELTA, "incorrect y for localCoordinates");
    }

    @Test
    public void testValidateWithMissingMipmaps() {

        final TileSpec tileSpec = TileSpec.fromJson(JSON_WITH_MISSING_MIPMAP_LEVELS);

        assertNotNull(tileSpec, "json parse returned null spec");
        assertEquals(EXPECTED_WIDTH, tileSpec.getWidth(), "invalid width parsed");
        assertThrows(IllegalArgumentException.class, () -> tileSpec.validate());
    }

    @Test
    public void testGetFirstChannel() {

        final TileSpec tileSpec = TileSpec.fromJson(JSON_WITH_UNSORTED_MIPMAP_LEVELS);
        assertNull(tileSpec.getFirstChannelName(), "incorrect first channel name with no channels");

        final String firstName = "DAPI";
        tileSpec.convertLegacyToChannel(firstName);
        assertEquals(firstName, tileSpec.getFirstChannelName(), "incorrect first channel name with 1 channel");

        tileSpec.addChannel(new ChannelSpec("TdTomato", 0.0, 0.0, new TreeMap<>(), null, null));
        tileSpec.addChannel(new ChannelSpec("ACQTdtomato", 0.0, 0.0, new TreeMap<>(), null, null));
        assertEquals(firstName, tileSpec.getFirstChannelName(), "incorrect first channel name with 3 channels");
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

        assertEquals(minX, tileSpec.getMinX(), MAX_DOUBLE_DELTA, "incorrect minX");
        assertEquals(minY, tileSpec.getMinY(), MAX_DOUBLE_DELTA, "incorrect minY");
        final double hackedDeltaUntilMPICBGLibIsFixed = 1.0;
        assertEquals(maxX, tileSpec.getMaxX(), hackedDeltaUntilMPICBGLibIsFixed, "incorrect maxX");
        assertEquals(maxY, tileSpec.getMaxY(), hackedDeltaUntilMPICBGLibIsFixed, "incorrect maxY");

        // sloppy
        tileSpec.deriveBoundingBox(64, true, true);

        assertEquals(minX, tileSpec.getMinX(), MAX_DOUBLE_DELTA, "incorrect minX");
        assertEquals(minY, tileSpec.getMinY(), MAX_DOUBLE_DELTA, "incorrect minY");
        assertEquals(maxX, tileSpec.getMaxX(), MAX_DOUBLE_DELTA, "incorrect maxX");
        assertEquals(maxY, tileSpec.getMaxY(), MAX_DOUBLE_DELTA, "incorrect maxY");

        final int iterations = 100;
        final long sloppyTime = getDerivationTime(tileSpec, true, iterations);
        final long meshTime = getDerivationTime(tileSpec, false, iterations);
        assertTrue((sloppyTime < meshTime), "sloppy derivation is not faster than mesh derivation");

        LOG.info("testDeriveBoundingBox: {} iterations, sloppy time: {}ms, mesh time: {}ms",
                 iterations, sloppyTime, meshTime);
    }

    @SuppressWarnings("SameParameterValue")
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
    private static final String EXPECTED_LABEL_A = "restart";
    private static final String EXPECTED_LABEL_B = "warped";
    private static final int EXPECTED_WIDTH = 99;
    private static final double MAX_DOUBLE_DELTA = 0.1;

    private static final String JSON_WITH_UNSORTED_MIPMAP_LEVELS =
            "{\n" +
            "  \"tileId\": \"" + EXPECTED_TILE_ID + "\",\n" +
            "  \"labels\": [\"" + EXPECTED_LABEL_A + "\",\"" + EXPECTED_LABEL_B + "\"],\n" +
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
            "  \"filterSpec\": {\n" +
            "    \"className\": \"org.janelia.alignment.filter.CLAHE\",\n" +
            "    \"parameters\": {\n" +
            "      \"fast\": \"true\",\n" +
            "      \"blockRadius\": \"500\",\n" +
            "      \"bins\": \"256\",\n" +
            "      \"slope\": \"2.5\"\n" +
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
