package org.janelia.render.client;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link IGridPaths} class.
 *
 * @author Eric Trautman
 */
public class IGridPathsTest {

    private File file;

    @Before
    public void setup() throws Exception {
        file = null;
    }

    @After
    public void tearDown() throws Exception {
        if (file != null) {
            if (! file.delete()) {
                LOG.warn("failed to delete " + file.getAbsolutePath());
            }
        }
    }

    @Test
    public void testSaveToFile() throws Exception {

        final int numberOfRows = 5;
        final int numberOfColumns = 6;
        final IGridPaths iGridPaths = new IGridPaths(numberOfRows, numberOfColumns);

        for (int row = 2; row < 4; row++) {
            for (int column = 1; column < 4; column++) {
                iGridPaths.addImage(new File("r" + row + "c" + column + ".txt"), row, column);
            }
        }

        validateSave(iGridPaths, numberOfRows, numberOfColumns);
    }

    @Test
    public void testAddImage() throws Exception {

        final Bounds layerBounds = new Bounds(47678.0, 16044.0, 3457.0, 253506.0, 121939.0, 3457.0);
        final int boxHeight = 8192;
        final int boxWidth = 8192;
        final SectionBoxBounds boxBounds = new SectionBoxBounds(3457.0, 8192, 8192, layerBounds);

        final IGridPaths iGridPaths = new IGridPaths(boxBounds.getNumberOfRows(), boxBounds.getNumberOfColumns());

        int row = boxBounds.getFirstRow();
        int column;
        for (int y = boxBounds.getFirstY(); y <= boxBounds.getLastY(); y += boxHeight) {

            column = boxBounds.getFirstColumn();

            for (int x = boxBounds.getFirstX(); x <= boxBounds.getLastX(); x += boxWidth) {
                iGridPaths.addImage(new File("/tmp/" + row + "/" + column + ".png"),
                                    row,
                                    column);
                column++;
            }

            row++;
        }

        validateSave(iGridPaths, boxBounds.getNumberOfRows(), boxBounds.getNumberOfColumns());
    }

    private void validateSave(final IGridPaths iGridPaths,
                              final int numberOfRows,
                              final int numberOfColumns) throws Exception {

        final File parentDirectory = new File(".").getAbsoluteFile();
        final Double z = (double) new Date().getTime();
        final File emptyFile = new File("empty.txt");
        file = iGridPaths.saveToFile(parentDirectory, z, emptyFile);

        final List<String> iGridLines = Files.readAllLines(file.toPath(),
                                                           Charset.defaultCharset());

        final int expectedLineCount = numberOfRows * numberOfColumns + 2;
        Assert.assertEquals("invalid number of lines", expectedLineCount, iGridLines.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(IGridPathsTest.class);

}
