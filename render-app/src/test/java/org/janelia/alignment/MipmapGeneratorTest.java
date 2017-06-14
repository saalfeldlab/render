package org.janelia.alignment;

import junit.framework.Assert;

import org.janelia.alignment.spec.ChannelSpec;
import org.janelia.alignment.spec.TileSpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Tests the {@link MipmapGenerator} class.
 *
 * @author Eric Trautman
 */
public class MipmapGeneratorTest {

    private File baseMipmapDirectory;

    @Before
    public void setUp() throws Exception {
        final File testDirectory = new File("src/test").getCanonicalFile();
        final SimpleDateFormat sdf = new SimpleDateFormat("'test_base_'yyyyMMddhhmmssSSS");
        baseMipmapDirectory = new File(testDirectory, sdf.format(new Date()));
        if (baseMipmapDirectory.mkdirs()) {
            LOG.info("created directory " + baseMipmapDirectory.getAbsolutePath());
        } else {
            throw new IllegalStateException("failed to create " + baseMipmapDirectory.getAbsolutePath());
        }
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursive(baseMipmapDirectory);
    }

    @Test
    public void testGetBaseMipmapFile() throws Exception {
        final MipmapGenerator mipmapGenerator = new MipmapGenerator(baseMipmapDirectory,
                                                                    Utils.JPEG_FORMAT,
                                                                    0.85f,
                                                                    false);

        final String sourcePath = "/groups/saalfeld/raw-data/stack-1/file1.tif";
        final String sourceUrl = "file:/groups/saalfeld/raw-data/stack-1/file1.tif";
        final File baseMipmapFile = mipmapGenerator.getMipmapBaseFile(sourceUrl, false);

        final String expectedFilePath = baseMipmapDirectory.getAbsolutePath() + sourcePath;
        Assert.assertEquals("invalid file path derived",
                            expectedFilePath, baseMipmapFile.getAbsolutePath());
    }

    @Test
    public void testGenerateMissingMipmapFiles() throws Exception {
        final File parametersFile = new File("src/test/resources/mipmap-test/generator_parameters.json");
        final MipmapGeneratorParameters parameters = MipmapGeneratorParameters.parseJson(parametersFile);
        parameters.initializeDerivedValues();

        final List<TileSpec> tileSpecList = parameters.getTileSpecs();

        Assert.assertEquals("incorrect number of tile specs parsed", 4, tileSpecList.size());

        final MipmapGenerator mipmapGenerator = new MipmapGenerator(baseMipmapDirectory,
                                                                    parameters.getFormat(),
                                                                    parameters.getQuality(),
                                                                    true);
        ImageAndMask consolidatedLevel1imageAndMask = null;
        ImageAndMask consolidatedLevel2imageAndMask = null;
        TileSpec tileSpec;
        ChannelSpec channelSpec;
        for (int i = 0; i < tileSpecList.size(); i++) {

            tileSpec = tileSpecList.get(i);
            channelSpec = tileSpec.getAllChannels().get(0);

            Assert.assertTrue("original tile spec is missing level 0 mipmap", channelSpec.hasMipmap(0));
            Assert.assertFalse("original tile spec already has level 1 mipmap", channelSpec.hasMipmap(1));
            Assert.assertFalse("original tile spec already has level 2 mipmap", channelSpec.hasMipmap(2));

            mipmapGenerator.generateMissingMipmapFiles(tileSpec, 2);

            Assert.assertTrue("updated tile spec lost level 0 mipmap", channelSpec.hasMipmap(0));
            Assert.assertTrue("updated tile spec is missing level 1 mipmap", channelSpec.hasMipmap(1));
            Assert.assertTrue("updated tile spec is missing level 2 mipmap", channelSpec.hasMipmap(2));
            Assert.assertFalse("updated tile spec should not have level 3 mipmap", channelSpec.hasMipmap(3));

            // Consolidation Test:
            // -  Tiles 0 and 1 (with zipped masks) should have the same (consolidated) level 1 and level 2 masks.
            // -  Tile 2 (with unzipped mask) should also have same (consolidated) level 1 and level 2 masks.
            // -  Tile 3 should have different level 1 and level 2 masks.

            if (i == 0) {
                consolidatedLevel1imageAndMask = channelSpec.getMipmap(1);
                consolidatedLevel2imageAndMask = channelSpec.getMipmap(2);
            } else if (i < 3) {
                validateMask(channelSpec, i, consolidatedLevel1imageAndMask, 1, true);
                validateMask(channelSpec, i, consolidatedLevel2imageAndMask, 2, true);
            } else { // i == 3
                validateMask(channelSpec, i, consolidatedLevel1imageAndMask, 1, false);
                validateMask(channelSpec, i, consolidatedLevel2imageAndMask, 2, false);
            }
        }

    }

    private void validateMask(final ChannelSpec channelSpec,
                              final int specIndex,
                              final ImageAndMask consolidatedImageAndMask,
                              final int level,
                              final boolean shouldBeTheSame) {
        final ImageAndMask imageAndMask = channelSpec.getMipmap(level);
        if (shouldBeTheSame) {
            Assert.assertEquals("level " + level + " mask for tile " + specIndex + " should have been consolidated",
                                consolidatedImageAndMask.getMaskUrl(), imageAndMask.getMaskUrl());
        } else {
            Assert.assertNotSame("level " + level + " mask for tile " + specIndex + " should NOT have been consolidated",
                                 consolidatedImageAndMask.getMaskUrl(), imageAndMask.getMaskUrl());
        }
    }

    private static boolean deleteRecursive(final File file) {

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

    private static final Logger LOG = LoggerFactory.getLogger(MipmapGeneratorTest.class);

}
