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

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.json.JsonUtils;
import org.junit.Assert;
import org.junit.Test;

import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

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

        Map.Entry<Integer, ImageAndMask> floorMipMap = tileSpec.getFloorMipmapEntry(3);
        Assert.assertNotNull("floor 3 mipmap entry is null", floorMipMap);
        Assert.assertEquals("invalid key for floor 3 mipmap entry",
                            new Integer(3), floorMipMap.getKey());

        floorMipMap = tileSpec.getFloorMipmapEntry(4);
        Assert.assertNotNull("floor 4 mipmap entry is null", floorMipMap);
        Assert.assertEquals("invalid key for floor 3 mipmap entry",
                            new Integer(3), floorMipMap.getKey());
    }

    @Test
    public void testLayoutData() throws Exception {
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId(EXPECTED_TILE_ID);
        final LayoutData layoutData = new LayoutData(12, "array-a", "camera-b", 4, 5, 6.0, 7.0, 8.0);
        tileSpec.setLayout(layoutData);

        final String json = JsonUtils.GSON.toJson(tileSpec);

        Assert.assertNotNull("json generation returned null string", json);

        final TileSpec parsedSpec = TileSpec.fromJson(json);

        Assert.assertNotNull("json parse returned null spec", parsedSpec);
        Assert.assertEquals("invalid tileId parsed", EXPECTED_TILE_ID, parsedSpec.getTileId());

        final LayoutData parsedLayoutData = parsedSpec.getLayout();
        Assert.assertNotNull("json parse returned null layout data", parsedLayoutData);
        Assert.assertEquals("bad sectionId value", layoutData.getSectionId(), parsedLayoutData.getSectionId());
        Assert.assertEquals("bad array value", layoutData.getTemca(), parsedLayoutData.getTemca());
        Assert.assertEquals("bad camera value", layoutData.getCamera(), parsedLayoutData.getCamera());
        Assert.assertEquals("bad row value", layoutData.getImageRow(), parsedLayoutData.getImageRow());
        Assert.assertEquals("bad col value", layoutData.getImageCol(), parsedLayoutData.getImageCol());
        Assert.assertEquals("bad stageX value", layoutData.getStageX(), parsedLayoutData.getStageX());
        Assert.assertEquals("bad stageY value", layoutData.getStageY(), parsedLayoutData.getStageY());
        Assert.assertEquals("bad rotation value", layoutData.getRotation(), parsedLayoutData.getRotation());

        parsedSpec.setBoundingBox(new Rectangle(11, 12, 21, 22));
        ImageAndMask imageAndMask = new ImageAndMask("src/test/resources/stitch-test/coll0075_row0021_cam1.png", null);
        parsedSpec.putMipmap(0, imageAndMask);
        final String layoutFileFormat = parsedSpec.toLayoutFileFormat();
        final String hackedFileFormat = layoutFileFormat.replaceFirst("\t[^\t]+coll0075_row0021_cam1.png",
                                                                      "\timage.png");
        final String expectedLayoutFormat =
                String.valueOf(layoutData.getSectionId()) + '\t' + 787716 + "\t1.0\t0.0\t" +
                layoutData.getStageX() + "\t0.0\t1.0\t" + layoutData.getStageY() + '\t' +
                layoutData.getImageCol() + '\t' + layoutData.getImageRow() + '\t' + layoutData.getCamera() +
                "\timage.png\t" + layoutData.getTemca() + '\t' + layoutData.getRotation();
        Assert.assertEquals("bad layout file format generated", expectedLayoutFormat, hackedFileFormat);
    }

    @Test
    public void testCoordinateTransformsWithAffineOnly() throws Exception {
        final byte[] jsonBytes = Files.readAllBytes(Paths.get("src/test/resources/tile-test/tile_with_only_affine_transforms.json"));
        final String json = new String(jsonBytes);
        final TileSpec tileSpec = TileSpec.fromJson(json);
        final Double expectedZ = tileSpec.getZ();

        final float localX = 30f;
        final float localY = 40f;
        final float[] worldCoordinates = tileSpec.getWorldCoordinates(localX, localY);

        Assert.assertNotNull("worldCoordinates are null", worldCoordinates);
        Assert.assertEquals("incorrect length for worldCoordinates", 3, worldCoordinates.length);
        Assert.assertEquals("incorrect z for worldCoordinates", expectedZ, worldCoordinates[2], MAX_DOUBLE_DELTA);

        final float[] localCoordinates = tileSpec.getLocalCoordinates(worldCoordinates[0], worldCoordinates[1]);

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

        final float localX = 30f;
        final float localY = 40f;
        final float[] worldCoordinates = tileSpec.getWorldCoordinates(localX, localY);

        Assert.assertNotNull("worldCoordinates are null", worldCoordinates);
        Assert.assertEquals("incorrect length for worldCoordinates", 3, worldCoordinates.length);
        Assert.assertEquals("incorrect z for worldCoordinates", expectedZ, worldCoordinates[2], MAX_DOUBLE_DELTA);

        final float[] localCoordinates = tileSpec.getLocalCoordinates(worldCoordinates[0], worldCoordinates[1]);

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

    private static final String EXPECTED_TILE_ID = "test-tile-id";
    private static final int EXPECTED_WIDTH = 99;
    private static final double MAX_DOUBLE_DELTA = 0.1;

    private static final String JSON_WITH_UNSORTED_MIPMAP_LEVELS =
            "{\n" +
            "  \"tileId\": " + EXPECTED_TILE_ID + ",\n" +
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
            "    type= \"list\",\n" +
            "    specList= [\n" +
            "      {\n" +
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
            "    type= \"list\",\n" +
            "    specList= [\n" +
            "      {\n" +
            "        \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "        \"dataString\": \"1 0 0 1 0 0\"\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}";
}
