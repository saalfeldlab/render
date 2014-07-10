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

import java.util.List;

/**
 * Tests the {@link RenderParameters} class.
 *
 * @author Eric Trautman
 */
public class RenderParametersTest {

    @Test
    public void testJsonProcessing() throws Exception {

        final RenderParameters parameters = getTestParameters();

        Assert.assertNotNull("json parse returned null parameters", parameters);
        Assert.assertEquals("invalid width parsed", EXPECTED_WIDTH, parameters.getWidth());

        final List<TileSpec> tileSpecs = parameters.getTileSpecs();
        Assert.assertNotNull("json parse returned null tileSpecs", tileSpecs);
        Assert.assertEquals("invalid number of tileSpecs parsed", 2, tileSpecs.size());

        // validate that out of order mipmap levels get sorted during parse/load
        final String generatedJson = parameters.toJson();
        Assert.assertEquals("re-serialized json does not match original", ORDERED_JSON, generatedJson);
    }

    private RenderParameters getTestParameters() {
        return RenderParameters.DEFAULT_GSON.fromJson(UNORDERED_JSON, RenderParameters.class);
    }

    private static final int EXPECTED_WIDTH = 5300;

    private static final String TOP_JSON =
            "{\n" +
            "  \"url\": \"file:///Users/trautmane/renderer-test.json\",\n" +
            "  \"res\": 64,\n" +
            "  \"out\": \"renderer-test-out.jpg\",\n" +
            "  \"x\": 0.0,\n" +
            "  \"y\": 0.0,\n" +
            "  \"width\": " + EXPECTED_WIDTH + ",\n" +
            "  \"height\": 2260,\n" +
            "  \"numThreads\": 8,\n" +
            "  \"scale\": 1.0,\n" +
            "  \"mipmapLevel\": 0,\n" +
            "  \"areaOffset\": false,\n" +
            "  \"quality\": 0.85,\n" +
            "  \"tileSpecs\": [\n" +
            "    {\n" +
            "      \"mipmapLevels\": {\n";

    private static final String UNORDERED_LEVELS_JSON =
            "        \"2\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec0-level2.png\"\n" +
            "        },\n" +
            "        \"0\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec0-level0.png\"\n" +
            "        },\n" +
            "        \"1\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec0-level1.png\"\n" +
            "        }\n";

    private static final String ORDERED_LEVELS_JSON =
            "        \"0\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec0-level0.png\"\n" +
            "        },\n" +
            "        \"1\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec0-level1.png\"\n" +
            "        },\n" +
            "        \"2\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec0-level2.png\"\n" +
            "        }\n";

    private static final String BOTTOM_JSON =
            "      },\n" +
            "      \"width\": -1,\n" +
            "      \"height\": -1,\n" +
            "      \"minIntensity\": 0.0,\n" +
            "      \"maxIntensity\": 255.0,\n" +
            "      \"transforms\": [\n" +
            "        {\n" +
            "          \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "          \"dataString\": \"1 0 0 1 0 0\"\n" +
            "        }\n" +
            "      ]\n" +
            "    },\n" +
            "    {\n" +
            "      \"mipmapLevels\": {\n" +
            "        \"0\": {\n" +
            "          \"imageUrl\": \"file:///Users/trautmane/spec1-level0.png\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"width\": -1,\n" +
            "      \"height\": -1,\n" +
            "      \"minIntensity\": 0.0,\n" +
            "      \"maxIntensity\": 255.0,\n" +
            "      \"transforms\": [\n" +
            "        {\n" +
            "          \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "          \"dataString\": \"1 0 0 1 1650 0\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}";

    private static final String UNORDERED_JSON = TOP_JSON + UNORDERED_LEVELS_JSON + BOTTOM_JSON;
    private static final String ORDERED_JSON = TOP_JSON + ORDERED_LEVELS_JSON + BOTTOM_JSON;
}
