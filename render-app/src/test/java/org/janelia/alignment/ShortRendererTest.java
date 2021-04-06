package org.janelia.alignment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the {@link ShortRenderer} class.
 *
 * @author Eric Trautman
 */
public class ShortRendererTest {

    private String modulePath;
    private File outputFile;

    @Before
    public void setup() throws Exception {
        final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        final String timestamp = TIMESTAMP.format(new Date());
        outputFile = new File("test-render-" + timestamp +".png").getCanonicalFile();
        modulePath = outputFile.getParentFile().getCanonicalPath();
    }

    @After
    public void tearDown() throws Exception {
        ArgbRendererTest.deleteTestFile(outputFile);
    }

    @Test
    public void testStitching() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_16_bit.png");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/16_bit_tiles.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "700",
                "--height", "400",
                "--scale", "0.5"
        };

        ShortRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = ArgbRendererTest.getDigestString(expectedFile);
        final String actualDigestString = ArgbRendererTest.getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }
}
