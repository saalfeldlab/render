package org.janelia.alignment;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link ShortRenderer} class.
 *
 * @author Eric Trautman
 */
public class ShortRendererTest {

    @TempDir
    Path tempDir;

    private File outputFile;

    @BeforeEach
    public void setup() {
        outputFile = tempDir.resolve("test-render.png").toFile();
    }

    @Test
    public void testStitching() throws Exception {

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/16_bit_tiles.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "700",
                "--height", "400",
                "--scale", "0.5"
        };

        ShortRenderer.renderUsingCommandLineArguments(args);

        assertTrue(outputFile.exists(), "stitched file " + outputFile.getAbsolutePath() + " not created");

        final File expectedFile = new File("src/test/resources/stitch-test/expected_stitched_16_bit.png");
//        org.janelia.alignment.ArgbRendererTest.updateExpectedFileSinceYouAreSureRecentChangeIsCorrect(expectedFile, outputFile);

        final String expectedDigestString = ArgbRendererTest.getDigestString(expectedFile);
        final String actualDigestString = ArgbRendererTest.getDigestString(outputFile);

        assertEquals(expectedDigestString, actualDigestString,
                     "stitched file MD5 hash differs from expected result");
    }
}
