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

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Tests the {@link Render} class.
 *
 * @author Eric Trautman
 */
public class RenderTest {

    private String modulePath;
    private File outputFile;

    @Before
    public void setup() throws Exception {
        final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        final String timestamp = TIMESTAMP.format(new Date());
        outputFile = new File("test-render-" + timestamp +".jpg").getCanonicalFile();
        modulePath = outputFile.getParentFile().getCanonicalPath();
    }

    @After
    public void tearDown() throws Exception {
        deleteTestFile(outputFile);
    }

    @Test
    public void testMain() throws Exception {

        final File expectedFile =
                new File(modulePath + "/src/test/resources/stitch-test/expected_stitched_4_tiles.jpg");

        final String[] args = {
                "--url", "src/test/resources/stitch-test/test_4_tiles.json",
                "--out", outputFile.getAbsolutePath(),
                "--width", "4576",
                "--height", "4173",
                "--scale", "0.05"
        };

        Render.main(args);

        Assert.assertTrue("stitched file " + outputFile.getAbsolutePath() + " not created", outputFile.exists());

        final String expectedDigestString = getDigestString(expectedFile);
        final String actualDigestString = getDigestString(outputFile);

        Assert.assertEquals("stitched file MD5 hash differs from expected result",
                            expectedDigestString, actualDigestString);
    }

    private String getDigestString(File file) throws Exception {
        final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
        FileInputStream fileInputStream = new FileInputStream(file);
        DigestInputStream digestInputStream = new DigestInputStream(fileInputStream, messageDigest);

        final byte[] buffer = new byte[8192];
        for (int bytesRead = 1; bytesRead > 0;) {
            bytesRead = digestInputStream.read(buffer);
        }

        final byte[] digestValue = messageDigest.digest();
        StringBuilder sb = new StringBuilder(digestValue.length);
        for (byte b : digestValue) {
            sb.append(b);
        }

        return sb.toString();
    }

    private void deleteTestFile(File file) {
        if ((file != null) && file.exists()) {
            if (file.delete()) {
                LOG.info("deleteTestFile: deleted " + file.getAbsolutePath());
            } else {
                LOG.info("deleteTestFile: failed to delete " + file.getAbsolutePath());
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(RenderTest.class);
}
