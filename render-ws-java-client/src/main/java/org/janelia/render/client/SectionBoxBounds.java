package org.janelia.render.client;

import java.io.Serializable;

import org.janelia.alignment.spec.Bounds;

/**
 * Derived bounds for a layer that has been split up into boxes with a fixed width and height.
 *
 * @author Eric Trautman
 */
public class SectionBoxBounds implements Serializable {

    private final int z;

    private final int firstRow;
    private final int lastRow;
    private final int firstColumn;
    private final int lastColumn;
    private final int firstX;
    private final int lastX;
    private final int firstY;
    private final int lastY;

    private final int numberOfRows;
    private final int numberOfColumns;

    private Integer firstGroupBox;
    private Integer lastGroupBox;

    /**
     * Basic constructor.
     *
     * @param  z            z value for the layer.
     * @param  boxWidth     width of each target box.
     * @param  boxHeight    height of each target box.
     * @param  layerBounds  world coordinate bounds for all tiles in the layer.
     */
    public SectionBoxBounds(final Double z,
                            final int boxWidth,
                            final int boxHeight,
                            final Bounds layerBounds) {

        this.z = z.intValue();

        this.firstColumn = (int) (layerBounds.getMinX() / boxWidth);
        this.lastColumn = (int) (layerBounds.getMaxX() / boxWidth);

        this.firstRow = (int) (layerBounds.getMinY() / boxHeight);
        this.lastRow = (int) (layerBounds.getMaxY() / boxHeight);

        this.numberOfRows = this.lastRow - this.firstRow + 1;
        this.numberOfColumns = this.lastColumn - this.firstColumn + 1;

        this.firstX = firstColumn * boxWidth;
        this.lastX = ((lastColumn + 1) * boxWidth) - 1;

        this.firstY = firstRow * boxHeight;
        this.lastY = ((lastRow + 1) * boxHeight) - 1;

        this.firstGroupBox = null;
        this.lastGroupBox = null;

    }

    /**
     * @param  row     box row.
     * @param  column  box column.
     *
     * @return true if the specified box is part of the group being rendered; otherwise false.
     */
    public boolean isInRenderGroup(final int row,
                                   final int column) {
        boolean isInGroup = true;
        if (firstGroupBox != null) {
            final int boxNumber = ((row - firstRow) * numberOfColumns) + (column - firstColumn);
            isInGroup = (boxNumber >= firstGroupBox) && (boxNumber <= lastGroupBox);
        }
        return isInGroup;
    }

    /**
     * Derives the range of boxes that should be rendered for the specified group.
     *
     * @param  renderGroup           index (1-n) that identifies portion of layer to render.
     * @param  numberOfRenderGroups  total number of portions being rendered for the layer.
     */
    public void setRenderGroup(final Integer renderGroup,
                               final Integer numberOfRenderGroups) {

        final int renderGroupIndex = renderGroup - 1;
        final int numberOfBoxes = numberOfRows * numberOfColumns;

        if (numberOfRenderGroups <= numberOfBoxes) {

            final int boxesPerGroup = numberOfBoxes / numberOfRenderGroups;
            final int groupsWithExtraBoxCount = (numberOfBoxes % numberOfRenderGroups);

            if (renderGroupIndex < groupsWithExtraBoxCount) {
                this.firstGroupBox = renderGroupIndex * (boxesPerGroup + 1);
                this.lastGroupBox = this.firstGroupBox + (boxesPerGroup + 1) - 1;
            } else {
                final int firstStandardBox = groupsWithExtraBoxCount * (boxesPerGroup + 1);
                final int groupsWithStandardBoxCount = renderGroupIndex - groupsWithExtraBoxCount;
                this.firstGroupBox = firstStandardBox + (groupsWithStandardBoxCount * boxesPerGroup);
                this.lastGroupBox = this.firstGroupBox + boxesPerGroup - 1;
            }

        } else if (renderGroupIndex < numberOfBoxes) {

            this.firstGroupBox = renderGroupIndex;
            this.lastGroupBox = this.firstGroupBox;

        } else {

            this.firstGroupBox = 0;
            this.lastGroupBox = -1;

        }

    }

    public int getZ() {
        return z;
    }

    public int getFirstRow() {
        return firstRow;
    }

    public int getLastRow() {
        return lastRow;
    }

    public int getFirstColumn() {
        return firstColumn;
    }

    public int getLastColumn() {
        return lastColumn;
    }

    public int getFirstX() {
        return firstX;
    }

    public int getLastX() {
        return lastX;
    }

    public int getFirstY() {
        return firstY;
    }

    public int getLastY() {
        return lastY;
    }

    public int getNumberOfColumns() {
        return numberOfColumns;
    }

    @Override
    public String toString() {
        return "z " + z + ", columns " + firstColumn + " to " + lastColumn + " (x: " + firstX + " to " + lastX +
               "), rows " + firstRow + " to " + lastRow + " (y: " + firstY + " to " + lastY +
               "), boxes " + firstGroupBox + " to " + lastGroupBox;
    }

}
