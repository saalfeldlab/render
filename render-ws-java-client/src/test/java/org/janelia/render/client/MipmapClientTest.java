package org.janelia.render.client;

import ij.process.ImageProcessor;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import org.janelia.alignment.ImageAndMask;
import org.janelia.alignment.loader.ImageJDefaultLoader;
import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.util.FileUtil;
import org.janelia.render.client.parameter.CommandLineParameters;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
    public void tearDown() {
        FileUtil.deleteRecursive(mipmapRootDirectory);
    }

    @Test
    public void testParameterParsing() throws Exception {
        CommandLineParameters.parseHelp(new MipmapClient.Parameters());
    }

    @Test
    public void testGenerateMissingMipmapFiles() throws Exception {

        final MipmapClient.Parameters parameters = new MipmapClient.Parameters();
        parameters.mipmap.rootDirectory = mipmapRootDirectory.getAbsolutePath();
        parameters.mipmap.maxLevel = 2;

        MipmapClient mipmapClient = new MipmapClient(parameters.renderWeb, parameters.mipmap);

        final ImageAndMask sourceImageAndMask = new ImageAndMask("src/test/resources/col0060_row0140_cam0.tif",
                                                                 "src/test/resources/mask.tif");
        final TileSpec tileSpec = new TileSpec();
        tileSpec.setTileId("test-mipmap-tile");
        final ChannelSpec channelSpec = new ChannelSpec();
        channelSpec.putMipmap(0, sourceImageAndMask);
        tileSpec.addChannel(channelSpec);
        tileSpec.setMipmapPathBuilder(mipmapClient.getMipmapPathBuilder());

        mipmapClient.generateMissingMipmapFiles(tileSpec);

        ImageProcessor imageProcessor = ImageJDefaultLoader.INSTANCE.load(sourceImageAndMask.getImageUrl());
        int expectedWidth;
        int expectedHeight;

        Map.Entry<Integer, ImageAndMask> mipmapEntry = null;
        ImageAndMask imageAndMask = null;
        String url;
        int level;

        for (level = 1; level < 3; level++) {
            expectedWidth = imageProcessor.getWidth() / 2;
            expectedHeight = imageProcessor.getHeight() / 2;

            mipmapEntry = channelSpec.getFloorMipmapEntry(level);
            imageAndMask = mipmapEntry.getValue();
            url = imageAndMask.getImageUrl();

            imageProcessor = ImageJDefaultLoader.INSTANCE.load(url);
            Assert.assertEquals("invalid width for level " + level + " image " + url,
                                expectedWidth, imageProcessor.getWidth());
            Assert.assertEquals("invalid height for level " + level + " image " + url,
                                expectedHeight, imageProcessor.getHeight());

            url = imageAndMask.getMaskUrl();
            imageProcessor = ImageJDefaultLoader.INSTANCE.load(url);
            Assert.assertEquals("invalid width for level " + level + " mask " + url,
                                expectedWidth, imageProcessor.getWidth());
            Assert.assertEquals("invalid height for level " + level + " mask " + url,
                                expectedHeight, imageProcessor.getHeight());
        }

        final Map.Entry<Integer, ImageAndMask> floor3Entry = channelSpec.getFloorMipmapEntry(level);
        Assert.assertEquals("invalid level returned for floor of non-existent level",
                            mipmapEntry.getKey(), floor3Entry.getKey());

        // --------------------------------------------------------------------
        // add another level and confirm that originally generated files remain

        final File previouslyGeneratedImageFile = new File(imageAndMask.getImageFilePath());
        final long expectedLastModified = previouslyGeneratedImageFile.lastModified();

        parameters.mipmap.rootDirectory = mipmapRootDirectory.getAbsolutePath();
        parameters.mipmap.maxLevel = level;
        mipmapClient = new MipmapClient(parameters.renderWeb, parameters.mipmap);
        tileSpec.setMipmapPathBuilder(mipmapClient.getMipmapPathBuilder());

        mipmapClient.generateMissingMipmapFiles(tileSpec);

        expectedWidth = imageProcessor.getWidth() / 2;
        expectedHeight = imageProcessor.getHeight() / 2;

        mipmapEntry = channelSpec.getFloorMipmapEntry(level);
        imageAndMask = mipmapEntry.getValue();
        url = imageAndMask.getImageUrl();

        imageProcessor = ImageJDefaultLoader.INSTANCE.load(url);
        Assert.assertEquals("invalid width for level " + level + " image " + url,
                            expectedWidth, imageProcessor.getWidth());
        Assert.assertEquals("invalid height for level " + level + " image " + url,
                            expectedHeight, imageProcessor.getHeight());

        url = imageAndMask.getMaskUrl();
        imageProcessor = ImageJDefaultLoader.INSTANCE.load(url);
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
}
