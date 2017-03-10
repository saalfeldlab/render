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
package org.janelia.render.client;

import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.spec.TileSpec;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link MipmapClient} class.
 *
 * @author Eric Trautman
 */
public class MipmapClientTest {

    private File mipmapRootDirectory;

    @Before
    public void setup() throws Exception {
        mipmapRootDirectory = createTestDirectory("test_mipmap_client");
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursive(mipmapRootDirectory);
    }

    @Test
    public void testGenerateMissingMipmapFiles() throws Exception {

        MipmapClient.CommonParameters commonParameters =
                new MipmapClient.CommonParameters(mipmapRootDirectory.getAbsolutePath(),
                                                  2);
        MipmapClient.Parameters parameters = new MipmapClient.Parameters(commonParameters, new ArrayList<Double>());
        MipmapClient mipmapClient = new MipmapClient(parameters);

        final ImageAndMask sourceImageAndMask = new ImageAndMask("src/test/resources/col0060_row0140_cam0.tif",
                                                                 "src/test/resources/mask.tif");
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId("test-mipmap-tile");
        tileSpec.putMipmap(0, sourceImageAndMask);
        tileSpec.setMipmapPathBuilder(mipmapClient.getMipmapPathBuilder());

        mipmapClient.generateMissingMipmapFiles(tileSpec);

        ImageProcessor imageProcessor = MipmapClient.loadImageProcessor(sourceImageAndMask.getImageUrl());
        int expectedWidth;
        int expectedHeight;

        Map.Entry<Integer, ImageAndMask> mipmapEntry = null;
        ImageAndMask imageAndMask = null;
        String url;
        int level;

        for (level = 1; level < 3; level++) {
            expectedWidth = imageProcessor.getWidth() / 2;
            expectedHeight = imageProcessor.getHeight() / 2;

            mipmapEntry = tileSpec.getFloorMipmapEntry(level);
            imageAndMask = mipmapEntry.getValue();
            url = imageAndMask.getImageUrl();

            imageProcessor = MipmapClient.loadImageProcessor(url);
            Assert.assertEquals("invalid width for level " + level + " image " + url,
                                expectedWidth, imageProcessor.getWidth());
            Assert.assertEquals("invalid height for level " + level + " image " + url,
                                expectedHeight, imageProcessor.getHeight());

            url = imageAndMask.getMaskUrl();
            imageProcessor = MipmapClient.loadImageProcessor(url);
            Assert.assertEquals("invalid width for level " + level + " mask " + url,
                                expectedWidth, imageProcessor.getWidth());
            Assert.assertEquals("invalid height for level " + level + " mask " + url,
                                expectedHeight, imageProcessor.getHeight());
        }

        final Map.Entry<Integer, ImageAndMask> floor3Entry = tileSpec.getFloorMipmapEntry(level);
        //noinspection ConstantConditions
        Assert.assertEquals("invalid level returned for floor of non-existent level",
                            mipmapEntry.getKey(), floor3Entry.getKey());

        // --------------------------------------------------------------------
        // add another level and confirm that originally generated files remain

        //noinspection ConstantConditions
        final File previouslyGeneratedImageFile = new File(imageAndMask.getImageFilePath());
        final long expectedLastModified = previouslyGeneratedImageFile.lastModified();

        commonParameters = new MipmapClient.CommonParameters(mipmapRootDirectory.getAbsolutePath(),
                                                             level);
        parameters = new MipmapClient.Parameters(commonParameters, new ArrayList<Double>());
        mipmapClient = new MipmapClient(parameters);
        tileSpec.setMipmapPathBuilder(mipmapClient.getMipmapPathBuilder());

        mipmapClient.generateMissingMipmapFiles(tileSpec);

        expectedWidth = imageProcessor.getWidth() / 2;
        expectedHeight = imageProcessor.getHeight() / 2;

        mipmapEntry = tileSpec.getFloorMipmapEntry(level);
        imageAndMask = mipmapEntry.getValue();
        url = imageAndMask.getImageUrl();

        imageProcessor = MipmapClient.loadImageProcessor(url);
        Assert.assertEquals("invalid width for level " + level + " image " + url,
                            expectedWidth, imageProcessor.getWidth());
        Assert.assertEquals("invalid height for level " + level + " image " + url,
                            expectedHeight, imageProcessor.getHeight());

        url = imageAndMask.getMaskUrl();
        imageProcessor = MipmapClient.loadImageProcessor(url);
        Assert.assertEquals("invalid width for level " + level + " mask " + url,
                            expectedWidth, imageProcessor.getWidth());
        Assert.assertEquals("invalid height for level " + level + " mask " + url,
                            expectedHeight, imageProcessor.getHeight());

        Assert.assertEquals("image file " + previouslyGeneratedImageFile.getAbsolutePath() +
                            " should NOT have been regenerated",
                            expectedLastModified, previouslyGeneratedImageFile.lastModified());

    }

    public static File createTestDirectory(final String baseName)
            throws IOException {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        final String timestamp = sdf.format(new Date());
        final File testDirectory = new File(baseName + "_" + timestamp).getCanonicalFile();
        if (! testDirectory.mkdir() ) {
            throw new IOException("failed to create " + testDirectory.getAbsolutePath());
        }
        return testDirectory;
    }

    public static boolean deleteRecursive(final File file) {

        boolean deleteSuccessful = true;

        if (file.isDirectory()){
            final File[] files = file.listFiles();
            if (files != null) {
                for (final File f : files) {
                    deleteSuccessful = deleteSuccessful && deleteRecursive(f);
                }
            }
        }

        if (file.delete()) {
            LOG.info("deleted " + file.getAbsolutePath());
        } else {
            LOG.warn("failed to delete " + file.getAbsolutePath());
            deleteSuccessful = false;
        }

        return deleteSuccessful;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MipmapClientTest.class);
}
