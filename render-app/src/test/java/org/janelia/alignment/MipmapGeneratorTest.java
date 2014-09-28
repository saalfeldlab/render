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

import junit.framework.Assert;
import org.junit.Test;

import java.io.File;

/**
 * Tests the {@link MipmapGenerator} class.
 *
 * @author Eric Trautman
 */
public class MipmapGeneratorTest {

    @Test
    public void testGetBaseMipmapFile() throws Exception {
        final File testDirectory = new File(".").getCanonicalFile();
        MipmapGenerator mipmapGenerator = new MipmapGenerator(testDirectory, Utils.JPEG_FORMAT, 0.85f);

        final String sourcePath = "/groups/saalfeld/raw-data/stack-1/file1.tif";
        final String sourceUrl = "file:/groups/saalfeld/raw-data/stack-1/file1.tif";
        final File baseMipmapFile = mipmapGenerator.getMipmapBaseFile(sourceUrl, false);

        final String expectedFilePath = testDirectory.getAbsolutePath() + sourcePath;
        Assert.assertEquals("invalid file path derived",
                            expectedFilePath, baseMipmapFile.getAbsolutePath());
    }

}
