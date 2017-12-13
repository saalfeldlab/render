package org.janelia.render.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.janelia.alignment.betterbox.BoxData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility to produce an iGrid file.
 *
 * See <a href="http://www.cs.jhu.edu/~misha/Code/DMG/Version3.5/iGrid.html">
 *         http://www.cs.jhu.edu/~misha/Code/DMG/Version3.5/iGrid.html
 *     </a>.
 *
 * @author Eric Trautman
 */
public class IGridPaths {

    private final int numberOfRows;
    private final int numberOfColumns;
    private final int numberOfPaths;
    private final List<String> pathList;

    public IGridPaths(final int numberOfRows,
                      final int numberOfColumns) {
        this.numberOfRows = numberOfRows;
        this.numberOfColumns = numberOfColumns;
        this.numberOfPaths = numberOfRows * numberOfColumns;
        this.pathList = new ArrayList<>(this.numberOfPaths);
    }

    public void addImage(final File imageFile,
                         final int row,
                         final int column)
            throws IllegalArgumentException {

        if ((row < 0) || (row >= numberOfRows)) {
            throw new IllegalArgumentException("invalid row (" + row + ") specified for " +
                                               imageFile.getAbsolutePath() +
                                               ", row values must be between 0 and " + (numberOfRows - 1));
        }

        if ((column < 0) || (column >= numberOfColumns)) {
            throw new IllegalArgumentException("invalid column (" + column + ") specified for " +
                                               imageFile.getAbsolutePath() +
                                               ", column values must be between 0 and " + (numberOfColumns - 1));
        }

        final int index = (row * numberOfColumns) + column;
        for (int i = pathList.size(); i < index; i++) {
            pathList.add(null);
        }

        pathList.add(imageFile.getAbsolutePath());
    }

    public void addBoxes(final String baseBoxPath,
                         final String pathSuffix,
                         final List<BoxData> boxes)
            throws IllegalArgumentException {

        boxes.stream()
                .sorted((o1, o2) -> {
                    int result = Integer.compare(o1.getRow(), o2.getRow());
                    if (result == 0) {
                        result = Integer.compare(o1.getColumn(), o2.getColumn());
                    }
                    return result; })
                .forEach(box -> addImage(box.getAbsoluteLevelFile(baseBoxPath, pathSuffix),
                                         box.getRow(),
                                         box.getColumn()));
    }

    public File saveToFile(final File parentDirectory,
                           final Double z,
                           final File emptyImage)
            throws IOException {

        if (! parentDirectory.exists()) {
            if (! parentDirectory.mkdirs()) {
                throw new IOException("failed to create " + parentDirectory.getAbsolutePath());
            }
        }

        if (! parentDirectory.canWrite()) {
            throw new IOException("not allowed to write to " + parentDirectory.getAbsolutePath());
        }

        final File file = new File(parentDirectory, z + ".iGrid");
        final String header = "Columns: " + numberOfColumns + "\nRows: " + numberOfRows + "\n";
        final String emptyImagePath = emptyImage.getAbsolutePath();

        try (final BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                                                                   Charset.forName("US-ASCII"))){
            writer.write(header);
            for (final String imagePath : pathList) {
                if (imagePath == null) {
                    writer.write(emptyImagePath);
                } else {
                    writer.write(imagePath);
                }
                writer.newLine();
            }

            for (int i = pathList.size(); i < numberOfPaths; i++) {
                writer.write(emptyImagePath);
                writer.newLine();
            }
        }

        LOG.info("saveToFile: exit, added {} paths to {}", numberOfPaths, file.getAbsolutePath());

        return file;
    }

    private static final Logger LOG = LoggerFactory.getLogger(IGridPaths.class);
}
