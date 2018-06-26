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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link BoundingBoxRenderer} class.
 *
 * @author Eric Trautman
 */
public class BoundingBoxRendererTest {

    @Test
    public void testRender() throws Exception {

        final String json =
                "{\n" +
                "  \"x\" : 0.0, \"y\" : 0.0, \"width\" : 400, \"height\" : 100, \"scale\" : 1.0,\n" +
                "  \"tileSpecs\" : [ {\n" +
                "    \"tileId\" : \"tile_a.1.0\",\n" +
                "    \"z\" : 1.0, \"minX\" : 0.0, \"minY\" : 0.0, \"maxX\" : 199.0, \"maxY\" : 99.0, \"width\" : 200.0, \"height\" : 100.0,\n" +
                "    \"mipmapLevels\" : { \"0\" : { \"imageUrl\" : \"src/test/resources/stitch-test/col0075_row0021_cam1.png\" } },\n" +
                "    \"transforms\" : { \"type\" : \"list\", \"specList\" : [ \n" +
                "      { \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\", \"dataString\" : \"1 0 0 1 0 0\" } ]\n" +
                "    }\n" +
                "  }, {\n" +
                "    \"tileId\" : \"tile_b.1.0\",\n" +
                "    \"z\" : 1.0, \"minX\" : 190.0, \"minY\" : 0.0, \"maxX\" : 389.0, \"maxY\" : 99.0, \"width\" : 200.0, \"height\" : 100.0,\n" +
                "    \"mipmapLevels\" : { \"0\" : { \"imageUrl\" : \"src/test/resources/stitch-test/col0076_row0021_cam0.png\" } },\n" +
                "    \"transforms\" : { \"type\" : \"list\", \"specList\" : [ \n" +
                "      { \"className\" : \"mpicbg.trakem2.transform.AffineModel2D\", \"dataString\" : \"1 0 0 1 190 0\" } ]\n" +
                "    }\n" +
                "  } ]\n" +
                "}";

        final RenderParameters renderParameters = RenderParameters.parseJson(json);

        final BufferedImage bufferedImage = renderParameters.openTargetImage();

        final Color boxColor = Color.GREEN;
        final BoundingBoxRenderer renderer = new BoundingBoxRenderer(renderParameters, boxColor);
        renderer.render(bufferedImage);

        final int[][] boxPoints = {
                {0, 0}, {99, 0}, {99, 99}, {0, 99}, {90, 0}
        };

        for (final int[] point : boxPoints) {
            Assert.assertEquals("invalid color for box point " + Arrays.toString(point),
                                boxColor.getRGB(), bufferedImage.getRGB(point[0], point[1]));
        }

        final int[][] spacePoints = {
                {10, 10}, {110, 10}
        };

        for (final int[] point : spacePoints) {
            Assert.assertEquals("invalid color for space point " + Arrays.toString(point),
                                0, bufferedImage.getRGB(point[0], point[1]));
        }

    }
}
