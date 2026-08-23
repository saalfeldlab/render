package org.janelia.alignment;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests the {@link ByteRenderer} class.
 */
public class ByteRendererTest {

    @TempDir
    Path tempDir;

    private File outputFile;

    @BeforeEach
    public void setup() {
        outputFile = tempDir.resolve("test-render.jpg").toFile();
    }

    @Test
    public void testSfovStitchForSinglePixelGap() throws Exception {

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/sfov_stitch_test.json",
                "--out", outputFile.getAbsolutePath(),
                "--x", "40000",
                "--y", "71800",
                "--width", "200",
                "--height", "1600",
                "--scale", "1.0"
        };

        ByteRenderer.renderUsingCommandLineArguments(args);

        assertTrue(outputFile.exists(), "stitched file " + outputFile.getAbsolutePath() + " not created");

        final ImageProcessor renderedIp = new ImagePlus(outputFile.getAbsolutePath()).getProcessor();
        for (int y = 0; y < renderedIp.getHeight(); y++) {
          for (int x = 0; x < renderedIp.getWidth(); x++) {
                final int pixel = renderedIp.get(x, y);
                if (pixel == 0) {
                    fail("pixel at " + x + ", " + y + " is 0");
                }
            }
        }
    }
}
