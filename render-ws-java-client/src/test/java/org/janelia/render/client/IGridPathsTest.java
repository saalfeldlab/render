package org.janelia.render.client;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

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

        final File parentDirectory = new File(".").getAbsoluteFile();
        final Double z = (double) new Date().getTime();
        final File emptyFile = new File("empty.txt");
        file = iGridPaths.saveToFile(parentDirectory, z, emptyFile);

        final List<String> iGridLines = Arrays.asList(
                new String(Files.readAllBytes(file.toPath())).split("\n"));

        final int expectedLineCount = numberOfRows * numberOfColumns + 2;
        Assert.assertEquals("invalid number of lines", expectedLineCount, iGridLines.size());
    }

    private static final Logger LOG = LoggerFactory.getLogger(IGridPathsTest.class);

}
