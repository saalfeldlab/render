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
package org.janelia.alignment.mipmap;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.LabelImageProcessorCache;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link org.janelia.alignment.mipmap.BoxMipmapGenerator} class.
 *
 * @author Eric Trautman
 */
public class BoxMipmapGeneratorTest {

    private File testDirectory;
    private File boxDirectory;
    private int z;
    private int boxWidth = 148;
    private int boxHeight = 148;
    private int lastRow = 3;
    private int lastColumn = 3;
    private int maxOverviewWidthAndHeight;
    private Bounds stackBounds;

    @Before
    public void setup() throws Exception {

        final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        final String timestamp = TIMESTAMP.format(new Date());
        testDirectory = new File("test-box-" + timestamp).getCanonicalFile();

        if (testDirectory.mkdirs()) {
            LOG.info("created directory " + testDirectory.getAbsolutePath());
        } else {
            throw new IllegalStateException("failed to create " + testDirectory.getAbsolutePath());
        }

        this.boxDirectory = new File(testDirectory, "test-project/test-stack/148x148");

        this.z = 11;
        this.boxWidth = 148;
        this.boxHeight = 148;
        this.lastRow = 3;
        this.lastColumn = 3;

        this.maxOverviewWidthAndHeight = 80;

        final double layerMaxX = ((lastColumn + 1) * boxWidth) - 1;
        final double layerMaxY = ((lastRow + 1) * boxHeight) - 1;
        this.stackBounds = new Bounds(0.0, 0.0, layerMaxX, layerMaxY);
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursive(testDirectory);
    }

    @Test
    public void testMipmapGenerator() throws Exception {

        BoxMipmapGenerator boxMipmapGenerator = new BoxMipmapGenerator(z,
                                                                       false,
                                                                       Utils.PNG_FORMAT,
                                                                       boxWidth,
                                                                       boxHeight,
                                                                       boxDirectory,
                                                                       0,
                                                                       0,
                                                                       lastRow,
                                                                       0,
                                                                       lastColumn,
                                                                       false);

        // Level 0:
        //
        //   - - - -
        //   - - A B
        //   - - C D
        //   - - - -

        for (int row = 1; row < 3; row++) {
            for (int column = 2; column < 4; column++) {
                boxMipmapGenerator.addSource(row, column, new File(boxDirectory, "0/"+z+"/"+row+"/"+column+".png"));
            }
        }

        // Level 1:
        //
        //   -- AB
        //   -- CD

        boxMipmapGenerator = validateNextLevel(boxMipmapGenerator, new int[][] {{0,1}, {1,1}});

        // Level 2:
        //
        //   AB
        //   CD

        boxMipmapGenerator = validateNextLevel(boxMipmapGenerator, new int[][] {{0,0}});

        final Path overviewDirPath = Paths.get(boxDirectory.getAbsolutePath(), "small");
        final File overviewFile = new File(overviewDirPath.toFile(), z + ".png").getAbsoluteFile();
        final boolean isOverviewGenerated = boxMipmapGenerator.generateOverview(maxOverviewWidthAndHeight,
                                                                                stackBounds,
                                                                                overviewFile);

        Assert.assertTrue("overview generated flag should be true", isOverviewGenerated);

        Assert.assertNotNull("overview " + overviewFile +
                             " should have been generated for level " + boxMipmapGenerator.getSourceLevel(),
                             overviewFile);

        Assert.assertTrue("overview " + overviewFile +
                          " generated for level " + boxMipmapGenerator.getSourceLevel() + " but does not exist",
                          overviewFile.exists());

    }

    @Test
    public void testSaveLabelImage() throws Exception {

        final int level = 9;
        final int row = 0;
        final int column = 0;

        final String[] args = {
                "--tile_spec_url", "src/test/resources/stitch-test/test_4_tiles_level_1.json",
                "--out", "test-label.png", // not used but required
                "--width", "4576",
                "--height", "4173",
                "--scale", "1.0"
        };

        final RenderParameters params = RenderParameters.parseCommandLineArgs(args);
        params.setBackgroundRGBColor(Color.WHITE.getRGB());
        final BufferedImage argbLabelImage = params.openTargetImage();
        final LabelImageProcessorCache cache =
                new LabelImageProcessorCache(1000000, false, false, params.getTileSpecs());

        ArgbRenderer.render(params, argbLabelImage, cache);

        final File outputFile = BoxMipmapGenerator.getImageFile(Utils.PNG_FORMAT,
                                                               boxDirectory,
                                                               level,
                                                               z,
                                                               row,
                                                               column);

        BoxMipmapGenerator.saveImage(argbLabelImage,
                                     outputFile,
                                     true,
                                     Utils.PNG_FORMAT);

        Assert.assertTrue("missing label image " + outputFile.getAbsolutePath(), outputFile.exists());

    }

    private BoxMipmapGenerator validateNextLevel(final BoxMipmapGenerator boxMipmapGenerator,
                                                 final int[][] expectedRowAndColumnPairs) throws Exception {

        final BoxMipmapGenerator nextLevelGenerator = boxMipmapGenerator.generateNextLevel();
        final int level = nextLevelGenerator.getSourceLevel();

        final List<File> missingFiles = new ArrayList<>();

        for (final int[] rowAndColumn : expectedRowAndColumnPairs) {
            final File file = new File(boxDirectory, level+"/"+z+"/"+rowAndColumn[0]+"/"+rowAndColumn[1]+".png");
            if (! file.exists()) {
                missingFiles.add(file);
            }
        }

        Assert.assertTrue("The following files were not generated for level " + level + ": " + missingFiles,
                          missingFiles.isEmpty());

        final Path overviewDirPath = Paths.get(boxDirectory.getAbsolutePath(), "small");
        final File overviewFile = new File(overviewDirPath.toFile(), z + ".png").getAbsoluteFile();
        final boolean isOverviewGenerated = boxMipmapGenerator.generateOverview(maxOverviewWidthAndHeight,
                                                                                stackBounds,
                                                                                overviewFile);

        Assert.assertFalse("overview generated flag should be false for level " + level, isOverviewGenerated);
        Assert.assertFalse("overview " + overviewFile + " should NOT have been generated for level " + level,
                           overviewFile.exists());

        return nextLevelGenerator;
    }

    // TODO: replace with Apache Commons FileUtils.delete
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

    private static final Logger LOG = LoggerFactory.getLogger(BoxMipmapGeneratorTest.class);
}
