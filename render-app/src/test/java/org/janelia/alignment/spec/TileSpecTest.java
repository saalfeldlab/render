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

import java.util.Map;

/**
 * Tests the {@link org.janelia.alignment.spec.TileSpec} class.
 *
 * @author Eric Trautman
 */
public class TileSpecTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final TileSpec tileSpec = getTileSpec(JSON_WITH_UNSORTED_MIPMAP_LEVELS);

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
        String[] values = {"array-a", "camera-b", "row-c", "col-d"};
        final LayoutData layoutData = new LayoutData(values[0], values[1], values[2], values[3]);
        tileSpec.setLayout(layoutData);

        final String json = JsonUtils.GSON.toJson(tileSpec);

        Assert.assertNotNull("json generation returned null string", json);

        final TileSpec parsedSpec = getTileSpec(json);

        Assert.assertNotNull("json parse returned null spec", parsedSpec);
        Assert.assertEquals("invalid tileId parsed", EXPECTED_TILE_ID, parsedSpec.getTileId());

        final LayoutData parsedLayoutData = parsedSpec.getLayout();
        Assert.assertNotNull("json parse returned null layout data", parsedLayoutData);
        Assert.assertEquals("bad array value", values[0], parsedLayoutData.getTemca());
        Assert.assertEquals("bad camera value", values[1], parsedLayoutData.getCamera());
        Assert.assertEquals("bad row value", values[2], parsedLayoutData.getImageRow());
        Assert.assertEquals("bad col value", values[3], parsedLayoutData.getImageCol());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithMissingMipmaps() throws Exception {

        final TileSpec tileSpec = getTileSpec(JSON_WITH_MISSING_MIPMAP_LEVELS);

        Assert.assertNotNull("json parse returned null spec", tileSpec);
        Assert.assertEquals("invalid width parsed", EXPECTED_WIDTH, tileSpec.getWidth());

        tileSpec.validate();
    }

    private TileSpec getTileSpec(String json) {
        return JsonUtils.GSON.fromJson(json, TileSpec.class);
    }

    private static final String EXPECTED_TILE_ID = "test-tile-id";
    private static final int EXPECTED_WIDTH = 99;

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
            "  \"transforms\": [\n" +
            "    {\n" +
            "      \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "      \"dataString\": \"1 0 0 1 0 0\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private static final String JSON_WITH_MISSING_MIPMAP_LEVELS =
            "{\n" +
            "  \"width\": " + EXPECTED_WIDTH + ",\n" +
            "  \"height\": -1,\n" +
            "  \"minIntensity\": 0.0,\n" +
            "  \"maxIntensity\": 255.0,\n" +
            "  \"transforms\": [\n" +
            "    {\n" +
            "      \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "      \"dataString\": \"1 0 0 1 0 0\"\n" +
            "    }\n" +
            "  ]\n" +
            "}";
}
