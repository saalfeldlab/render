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
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles_16_bit.png");

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

        ShortRenderer.renderUsingCommandLineArguments(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = ArgbRendererTest.getDigestString(expectedFile);
        final String actualDigestString = ArgbRendererTest.getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }
}
