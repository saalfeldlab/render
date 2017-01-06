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
    private final int boxWidth;
    private final int boxHeight;

    private final int firstRow;
    private final int lastRow;
    private final int firstColumn;
    private final int lastColumn;

    private int firstX;
    private int lastX;
    private int firstY;
    private int lastY;

    private boolean hasRenderGroup;

    private int rowsAndColumnsPerArea;
    private int firstAreaRow;
    private int firstAreaColumn;

    private int firstGroupArea;
    private int lastGroupArea;
    private int numberOfAreaColumns;

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
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;

        this.firstColumn = layerBounds.getMinX() > 0 ? (int) (layerBounds.getMinX() / boxWidth) : 0;
        this.lastColumn = (int) (layerBounds.getMaxX() / boxWidth);


        this.firstRow = layerBounds.getMinY() > 0 ? (int) (layerBounds.getMinY() / boxHeight) : 0;
        this.lastRow = (int) (layerBounds.getMaxY() / boxHeight);

        setDerivedValues();

        this.hasRenderGroup = false;
        this.firstGroupArea = -1;
        this.lastGroupArea = -1;
        this.numberOfAreaColumns = -1;
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
        if (hasRenderGroup) {
            final int areaRow = row / rowsAndColumnsPerArea;
            final int areaColumn = column / rowsAndColumnsPerArea;
            final int areaNumber = ((areaRow - firstAreaRow) * numberOfAreaColumns) + (areaColumn - firstAreaColumn);
            isInGroup = (areaNumber >= firstGroupArea) && (areaNumber <= lastGroupArea);
        }
        return isInGroup;
    }

    /**
     * Derives the range of boxes that should be rendered for the specified group.
     *
     * @param  renderGroup           index (1-n) that identifies portion of layer to render.
     * @param  numberOfRenderGroups  total number of portions being rendered for the layer.
     * @param  maxLevel              maximum mipmap level being generated.
     */
    public void setRenderGroup(final int renderGroup,
                               final int numberOfRenderGroups,
                               final int maxLevel) {

        final int renderGroupIndex = renderGroup - 1;

        rowsAndColumnsPerArea = (int) Math.pow((double) 2, (double) maxLevel);

        firstAreaRow = firstRow / rowsAndColumnsPerArea;
        firstAreaColumn = firstColumn / rowsAndColumnsPerArea;
        final int lastAreaRow = lastRow / rowsAndColumnsPerArea;
        final int lastAreaColumn = lastColumn / rowsAndColumnsPerArea;

        final int numberOfAreaRows = lastAreaRow - firstAreaRow + 1;
        numberOfAreaColumns = lastAreaColumn - firstAreaColumn + 1;
        final int numberOfAreas = numberOfAreaRows * numberOfAreaColumns;

        if (numberOfRenderGroups <= numberOfAreas) {

            final int areasPerGroup = numberOfAreas / numberOfRenderGroups;
            final int groupsWithExtraAreaCount = (numberOfAreas % numberOfRenderGroups);

            if (renderGroupIndex < groupsWithExtraAreaCount) {
                firstGroupArea = renderGroupIndex * (areasPerGroup + 1);
                lastGroupArea = firstGroupArea + (areasPerGroup + 1) - 1;
            } else {
                final int firstStandardArea = groupsWithExtraAreaCount * (areasPerGroup + 1);
                final int groupsWithStandardAreaCount = renderGroupIndex - groupsWithExtraAreaCount;
                firstGroupArea = firstStandardArea + (groupsWithStandardAreaCount * areasPerGroup);
                lastGroupArea = firstGroupArea + areasPerGroup - 1;
            }

        } else if (renderGroupIndex < numberOfAreas) {

            firstGroupArea = renderGroupIndex;
            lastGroupArea = firstGroupArea;

        } else {

            firstGroupArea = 0;
            lastGroupArea = -1;

        }

        hasRenderGroup = true;
    }

//    /**
//     * @param  row     box row.
//     * @param  column  box column.
//     *
//     * @return true if the specified box is within these bounds; otherwise false.
//     */
//    public boolean isInBounds(final int row,
//                              final int column) {
//        return (row >= firstRow) && (row <= lastRow) && (column >= firstColumn) && (column <= lastColumn);
//    }
//
//    /**
//     * Derives the range of boxes that should be rendered for the specified group.
//     *
//     * @param  renderGroup           index (1-n) that identifies portion of layer to render.
//     * @param  numberOfRenderGroups  total number of portions being rendered for the layer.
//     * @param  maxLevel              maximum mipmap level being generated.
//     */
//    public void setRenderGroup2(final int renderGroup,
//                               final int numberOfRenderGroups,
//                               final int maxLevel) {
//
//        final int renderGroupIndex = renderGroup - 1;
//
//        final int rowsAndColumnsPerArea = (int) Math.pow((double) 2, (double) maxLevel);
//
//        final int firstAreaRow = firstRow / rowsAndColumnsPerArea;
//        final int firstAreaColumn = firstColumn  / rowsAndColumnsPerArea;
//        final int lastAreaRow = lastRow / rowsAndColumnsPerArea;
//        final int lastAreaColumn = lastColumn / rowsAndColumnsPerArea;
//
//        final int numberOfAreaRows = lastAreaRow - firstAreaRow + 1;
//        final int numberOfAreaColumns = lastAreaColumn - firstAreaColumn + 1;
//        final int numberOfAreas = numberOfAreaRows * numberOfAreaColumns;
//
//        if (numberOfRenderGroups <= numberOfAreas) {
//
//            final int areaRowsPerGroup;
//            final int areaColumnsPerGroup;
//            if (numberOfRenderGroups <= numberOfAreaRows) {
//                areaRowsPerGroup = (numberOfAreaRows + (numberOfRenderGroups - 1)) / numberOfRenderGroups;
//                areaColumnsPerGroup = numberOfAreaColumns;
//            } else if (numberOfRenderGroups <= numberOfAreaColumns) {
//                areaRowsPerGroup = numberOfAreaRows;
//                areaColumnsPerGroup = (numberOfAreaColumns + (numberOfRenderGroups - 1)) / numberOfRenderGroups;
//            } else if (numberOfAreaColumns > numberOfAreaRows) {
//                areaRowsPerGroup = 1;
//                areaColumnsPerGroup = numberOfRenderGroups / numberOfAreaRows;
//            } else {
//                areaColumnsPerGroup = 1;
//                areaRowsPerGroup = numberOfRenderGroups / numberOfAreaColumns;
//            }
//
//
//
//        } else if (renderGroupIndex < numberOfAreas) {
//
//            final int row = (renderGroupIndex / numberOfAreaColumns) + firstAreaRow;
//            final int column = (renderGroupIndex % numberOfAreaColumns) + firstAreaColumn;
//
//            firstRow = row;
//            lastRow = row;
//            firstColumn = column;
//            lastColumn = column;
//
//        } else {
//
//            firstRow = 0;
//            lastRow = -1;
//            firstColumn = 0;
//            lastColumn = -1;
//
//        }
//
//        setDerivedValues();
//    }

    public int getZ() {
        return z;
    }

    public int getFirstRow() {
        return firstRow;
    }

    public int getLastRow() {
        return lastRow;
    }

    public int getNumberOfRows() {
        return lastRow + 1;
    }

    public int getFirstColumn() {
        return firstColumn;
    }

    public int getLastColumn() {
        return lastColumn;
    }

    public int getNumberOfColumns() {
        return lastColumn + 1;
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

    @Override
    public String toString() {
        return "z " + z + ", columns " + firstColumn + " to " + lastColumn + " (x: " + firstX + " to " + lastX +
               "), rows " + firstRow + " to " + lastRow + " (y: " + firstY + " to " + lastY + ")";
    }

    private void setDerivedValues() {
        this.firstX = firstColumn * boxWidth;
        this.lastX = ((lastColumn + 1) * boxWidth) - 1;

        this.firstY = firstRow * boxHeight;
        this.lastY = ((lastRow + 1) * boxHeight) - 1;
    }

}
