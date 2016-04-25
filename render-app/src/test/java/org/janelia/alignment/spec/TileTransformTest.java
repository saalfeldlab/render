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

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link TileTransform} class.
 *
 * @author Eric Trautman
 */
public class TileTransformTest {

    @Test
    public void testJsonProcessing() throws Exception {
        validateParsing("leaf", JSON_WITH_LEAF_TRANSFORM, LeafTransformSpec.class);
        validateParsing("list", JSON_WITH_LIST_TRANSFORM, ListTransformSpec.class);
    }

    private void validateParsing(final String context,
                                 final String json,
                                 final Class expectedTransformClass) {

        final TileTransform tileTransform = TileTransform.fromJson(json);

        Assert.assertNotNull("null object parsed for " + context, tileTransform);
        Assert.assertEquals("invalid tileId parsed for " + context,
                            EXPECTED_TILE_ID, tileTransform.getTileId());

        final TransformSpec transformSpec = tileTransform.getTransform();
        Assert.assertNotNull("null transform spec parsed for " + context, transformSpec);
        Assert.assertEquals("invalid transform spec parsed for " + context,
                            expectedTransformClass, transformSpec.getClass());

    }

    private static final String EXPECTED_TILE_ID = "test-tile-id";

    private static final String JSON_WITH_LEAF_TRANSFORM =
            "{\n" +
            "  \"tileId\": \"" + EXPECTED_TILE_ID + "\",\n" +
            "  \"transform\": {\n" +
            "    \"className\": \"mpicbg.trakem2.transform.AffineModel2D\",\n" +
            "    \"dataString\": \"1 0 0 1 0 0\"\n" +
            "  }\n" +
            "}";

    private static final String JSON_WITH_LIST_TRANSFORM =
            "{\n" +
            "  \"tileId\": \"" + EXPECTED_TILE_ID + "\",\n" +
            "  \"transform\": {\n" +
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
