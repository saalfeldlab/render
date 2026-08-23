package org.janelia.alignment;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link BoundingBoxRenderer} class.
 *
 * @author Eric Trautman
 */
public class BoundingBoxRendererTest {

    @Test
    public void testRender() {
        final RenderParameters renderParameters = RenderParameters.parseJson(TEST_JSON);

        final BufferedImage bufferedImage = renderParameters.openTargetImage();

        final Color boxColor = Color.GREEN;
        final BoundingBoxRenderer renderer = new BoundingBoxRenderer(renderParameters, boxColor);
        renderer.render(bufferedImage);

        final int[][] boxPoints = {
                {0, 0}, {99, 0}, {99, 99}, {0, 99}, {90, 0}
        };

        for (final int[] point : boxPoints) {
            assertEquals(boxColor.getRGB(), bufferedImage.getRGB(point[0], point[1]),
                         "invalid color for box point " + Arrays.toString(point));
        }

        final int[][] spacePoints = {
                {10, 10}, {110, 10}
        };

        for (final int[] point : spacePoints) {
            assertEquals(0, bufferedImage.getRGB(point[0], point[1]),
                         "invalid color for space point " + Arrays.toString(point));
        }

    }

    private static final String TEST_JSON =
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

    /**
     * Generates PNG files showing bounding-box renders at several scales so that tile-id
     * label readability can be compared visually.
     *
     * <p>Output files are written to the system temp directory.  The path of each generated
     * file is printed to stdout.</p>
     *
     * <p>Usage: run as a standard Java application (no arguments required).</p>
     */
    public static void main(final String[] args) throws Exception {

        // Scales to render — covering a wide range so readability can be compared easily.
        final double[] scales = {1.0, 0.8, 0.6, 0.4 };

        for (final double scale : scales) {
            // Load fresh parameters for each scale so that canvas dimensions are recomputed.
            final RenderParameters renderParameters = RenderParameters.parseJson(TEST_JSON);

            // Override the scale.
            renderParameters.setScale(scale);
            renderParameters.initializeDerivedValues();

            final BufferedImage targetImage = renderParameters.openTargetImage();

            final BoundingBoxRenderer renderer = new BoundingBoxRenderer(renderParameters, Color.GREEN);
            renderer.render(targetImage);

            final File outputFile = new File("/Users/trautmane/Desktop",
                                             String.format("bounding_box_scale_%f.png", scale));
            ImageIO.write(targetImage, "PNG", outputFile);

            System.out.printf("scale=%-5s  image size=%4dx%-4d  file=%s%n",
                              scale,
                              targetImage.getWidth(),
                              targetImage.getHeight(),
                              outputFile.getAbsolutePath());
        }

    }

}
