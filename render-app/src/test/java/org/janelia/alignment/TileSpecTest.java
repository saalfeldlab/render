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
package org.janelia.alignment;

import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * Tests the {@link TileSpec} class.
 *
 * @author Eric Trautman
 */
public class TileSpecTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final TileSpec tileSpec = getTileSpec(JSON_WITH_UNSORTED_MIPMAP_LEVELS);

        Assert.assertNotNull("json parse returned null spec", tileSpec);
        Assert.assertEquals("invalid width parsed", EXPECTED_WIDTH, tileSpec.getWidth());

        final Map.Entry<Integer, ImageAndMask> firstMipMap = tileSpec.getFirstMipMapEntry();
        Assert.assertNotNull("first mipmap entry is null", firstMipMap);
        Assert.assertEquals("mipmap sorting failed, unexpected first entry returned",
                            new Integer(0), firstMipMap.getKey());

        final Map.Entry<Integer, ImageAndMask> floorMipMap = tileSpec.getFloorMipMapEntry(3);
        Assert.assertNotNull("floor 3 mipmap entry is null", floorMipMap);
        Assert.assertEquals("invalid key for floor 3 mipmap entry",
                            new Integer(2), floorMipMap.getKey());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateWithMissingMipmaps() throws Exception {

        final TileSpec tileSpec = getTileSpec(JSON_WITH_MISSING_MIPMAP_LEVELS);

        Assert.assertNotNull("json parse returned null spec", tileSpec);
        Assert.assertEquals("invalid width parsed", EXPECTED_WIDTH, tileSpec.getWidth());

        tileSpec.validate();
    }

    private TileSpec getTileSpec(String json) {
        return RenderParameters.DEFAULT_GSON.fromJson(json, TileSpec.class);
    }

    private static final int EXPECTED_WIDTH = 99;

    private static final String JSON_WITH_UNSORTED_MIPMAP_LEVELS =
            "{\n" +
            "  \"mipmapLevels\": {\n" +
            "    \"2\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level2.png\"\n" +
            "    },\n" +
            "    \"0\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level0.png\"\n" +
            "    },\n" +
            "    \"1\": {\n" +
            "      \"imageUrl\": \"file:///Users/trautmane/spec0-level1.png\"\n" +
            "    }\n" +
            "  },\n" +
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
