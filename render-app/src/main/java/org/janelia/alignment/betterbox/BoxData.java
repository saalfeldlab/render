package org.janelia.alignment.betterbox;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Key information about a mipmap pyramid box that can be rendered from stack source data.
 *
 * The provided {@link #compareTo} method supports sorting boxes so that boxes that share a common
 * parent (and are therefore geographically close) are kept near each other in the ordering.
 *
 * @author Eric Trautman
 */
public class BoxData
        implements Serializable, Comparable<BoxData> {

    private final double z;
    private final int level;
    private final int row;
    private final int column;

    /** Total number of children for this box's parent. */
    private int numberOfSiblings;

    /** The existing children for this box (0: upper left, 1: upper right, 2: lower left, 3: lower right). */
    private final Map<Integer, BoxData> indexToChildMap;

    /** Sorted ordering of this box within the list of all boxes in the same layer (z) and mipmap level. */
    private Integer layerLevelIndex;

    /**
     * Basic constructor.
     *
     * @param  z       layer for this box.
     * @param  level   mipmap level for this box.
     * @param  row     row for this box.
     * @param  column  column for this box.
     */
    public BoxData(final double z,
                   final int level,
                   final int row,
                   final int column) {
        this.z = z;
        this.level = level;
        this.row = row;
        this.column = column;
        this.layerLevelIndex = null;
        this.indexToChildMap = new TreeMap<>();
        this.numberOfSiblings = 0;
    }

    public double getZ() {
        return z;
    }

    public int getLevel() {
        return level;
    }

    public int getRow() {
        return row;
    }

    public int getColumn() {
        return column;
    }

    /**
     * @return total number of children for this box's parent.
     *
     * @throws IllegalStateException
     *   if the sibling count has not been set for this box (see {@link #updateSiblingCountForAllChildren()}).
     */
    public int getNumberOfSiblings()
            throws IllegalStateException {
        if (numberOfSiblings == 0) {
            throw new IllegalStateException("sibling count for box " + this + " has not been set");
        }
        return numberOfSiblings;
    }

    /**
     * @return sorted ordering of this box within the list of all boxes in the same layer (z) and mipmap level.
     */
    public int getLayerLevelIndex()
            throws IllegalStateException {
        if (layerLevelIndex == null) {
            throw new IllegalStateException("layer level index for box " + this + " has not been set");
        }
        return layerLevelIndex;
    }

    public void setLayerLevelIndex(final int layerLevelIndex) {
        this.layerLevelIndex = layerLevelIndex;
    }

    public boolean hasChildren() {
        return (getChildCount() > 0);
    }

    public int getChildCount() {
        return indexToChildMap.size();
    }

    /**
     * @return list of children for this box
     *         ordered by parent index (0: upper left, 1: upper right, 2: lower left, 3: lower right)
     */
    public List<BoxData> getChildren() {
        return new ArrayList<>(indexToChildMap.values());
    }

    /**
     * Adds the specified box as a child of this box.
     *
     * @param  child  child box to add.
     *
     * @throws IllegalStateException
     *   if the a child with the same parent index has already been added to this box.
     */
    public void addChild(final BoxData child)
            throws IllegalStateException {

        final BoxData existingChild = indexToChildMap.put(child.getParentIndex(), child);

        if (existingChild != null) {
            throw new IllegalStateException(
                    "child [" + existingChild.getParentIndex() + "] " + existingChild +
                    " already exists for parent " + this);
        }
    }

    /**
     * Updates the sibling count for all of this box's children.
     */
    public void updateSiblingCountForAllChildren() {
        final int siblingCount = indexToChildMap.size();
        for (final BoxData sibling : indexToChildMap.values()) {
            sibling.numberOfSiblings = siblingCount;
        }
    }

    /**
     * @return the index of this box (0: upper left, 1: upper right, 2: lower left, 3: lower right)
     *         within its parent.
     */
    public int getParentIndex() {
        return ((row % 2) * 2) + (column % 2);
    }

    /**
     * @return mipmap level of this box's parent.
     */
    public int getParentLevel() {
        return level + 1;
    }

    /**
     * @return row of this box's parent.
     */
    public int getParentRow() {
        return row / 2;
    }

    /**
     * @return column of this box's parent.
     */
    public int getParentColumn() {
        return column / 2;
    }

    /**
     * @return new instance of this box's parent data.
     */
    public BoxData getParentBoxData() {
        return new BoxData(z,
                           getParentLevel(),
                           getParentRow(),
                           getParentColumn());
    }

    /**
     * Builds the web service sub-path for this box (e.g. '/z/1/box/54954,58314,7293,9271,0.25').
     * This sub-path can be used to render the box by appending it to the base URL for the box's stack.
     *
     * The format of the sub-path is:
     * <pre>
     *   /z/[z]/box/[x],[y],[width],[height],[scale]
     * </pre>
     *
     * @param  boxWidth   width of all boxes in the mipmap pyramid.
     * @param  boxHeight  height of all boxes in the mipmap pyramid.
     *
     * @return this box's service sub-path for rendering.
     */
    public String getServicePath(final int boxWidth,
                                 final int boxHeight) {

        final double scale = 1.0 / Math.pow(2, level);
        final double fullScaleWidth = boxWidth / scale;
        final double fullScaleHeight = boxHeight / scale;
        final int x = (int) Math.floor(column * fullScaleWidth);
        final int y = (int) Math.floor(row * fullScaleHeight);

        return "/z/" + z + "/box/" + x + ',' + y + ',' +
               (int) Math.ceil(fullScaleWidth) + ',' + (int) Math.ceil(fullScaleHeight) + ',' + scale;
    }

    /**
     * Builds the CATMAID LargeDataTileSource sub-path for this box (e.g. '/0/1015/3/312').
     *
     * The format of the sub-path is:
     * <pre>
     *   /[level]/[z]/[row]/[column]
     * </pre>
     *
     * @return this box's level sub-path for storage.
     */
    public String getLevelPath() {
        return "/" + level + '/' + (int) z + '/' + row + '/' + column;
    }

    /**
     * Builds a fully resolved file object for this box.
     *
     * @param  baseBoxPath    the base path for all boxes being rendered
     *                        (e.g. /nrs/spc/rendered_boxes/spc/aibs_mm2_data/1024x1024).
     *
     * @param  boxPathSuffix  the suffix (format extension including '.') to append to each box path (e.g. '.jpg').
     *
     * @return fully resolved file object for this box.
     */
    public File getAbsoluteLevelFile(final String baseBoxPath,
                                     final String boxPathSuffix) {
        return Paths.get(baseBoxPath, getLevelPath() + boxPathSuffix).toAbsolutePath().toFile();
    }

    /**
     * Builds a delimited string representation of this box's core data elements.
     *
     * The format of a comma-delimited string is:
     * <pre>
     *   [level],[z],[row],[column],[number of siblings],[child index list]
     * </pre>
     *
     * @param  d  delimiter used to separate data fields.
     *
     * @return delimited string representation of this box's core data elements.
     */
    public String toDelimitedString(final char d) {
        final StringBuilder sb = new StringBuilder();
        sb.append(level).append(d).append(z).append(d);
        sb.append(row).append(d).append(column).append(d).append(numberOfSiblings).append(d);
        indexToChildMap.keySet().forEach(sb::append);
        return sb.toString();
    }

    /**
     * Builds a comma-delimited string representation of this box
     * that can be deserialized by the {@link #fromString} method.
     *
     * @return string representation of this box.
     */
    @Override
    public String toString() {
        return toDelimitedString(',');
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BoxData boxData = (BoxData) o;
        return row == boxData.row &&
               column == boxData.column &&
               level == boxData.level &&
               Double.compare(boxData.z, z) == 0;
    }

    @Override
    public int hashCode() {
        // NOTE: does not worry about z since most box maps and sets should only have data for a single z
        return (31 * ((31 * level) + row)) + column;
    }

    /**
     * Sorts boxes so that boxes that share a common parent (and are therefore geographically close)
     * are kept near each other in the ordering.
     *
     * @param  that  box for comparison.
     *
     * @return  a negative integer, zero, or a positive integer as this box
     *          is less than, equal to, or greater than the specified box.
     */
    @Override
    public int compareTo(final BoxData that) {
        int result = Integer.compare((int) this.z, (int) that.z);
        if (result == 0) {
            result = Integer.compare(this.level, that.level);
            if (result == 0) {
                // use parent row and column to keep sibling boxes closer together
                result = Integer.compare(this.getParentRow(), that.getParentRow());
                if (result == 0) {
                    result = Integer.compare(this.getParentColumn(), that.getParentColumn());
                    if (result == 0) {
                        result = Integer.compare(this.row, that.row);
                        if (result == 0) {
                            result = Integer.compare(this.column, that.column);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Deserializes the specified string into a box instance.
     *
     * @param  boxDataString  comma-delimited representation of a box (see {@link #toDelimitedString}).
     *
     * @return box instance built from the specified string.
     *
     * @throws IllegalArgumentException
     *   if the string cannot be parsed into a box instance for any reason.
     */
    public static BoxData fromString(final String boxDataString)
            throws IllegalArgumentException {

        final String[] stringElements = PATH_PATTERN.split(boxDataString);

        if (stringElements.length < 5) {
            throw new IllegalArgumentException(
                    "box data string '" + boxDataString + "' must have at least 5 comma separated fields");
        }

        final int level = Integer.parseInt(stringElements[0]);
        final double z = Double.parseDouble(stringElements[1]);
        final int row = Integer.parseInt(stringElements[2]);
        final int column = Integer.parseInt(stringElements[3]);
        final int numberOfSiblings = Integer.parseInt(stringElements[4]);

        final BoxData boxData = new BoxData(z, level, row, column);
        boxData.numberOfSiblings = numberOfSiblings;

        if (level > 0) {

            if (stringElements.length < 6) {
                throw new IllegalArgumentException(
                        "level " + level + " box data string '" + boxDataString + "' is missing child index field");
            }

            for (final char childIndexValue : stringElements[5].toCharArray()) {
                final int childIndex = Character.getNumericValue(childIndexValue);
                final int childLevel = level - 1;
                final int childRow = (row * 2) + (childIndex / 2);
                final int childColumn = (column * 2) + (childIndex % 2);
                boxData.addChild(new BoxData(z, childLevel, childRow, childColumn));
            }

            boxData.updateSiblingCountForAllChildren();
        }

        return boxData;
    }

    private static final Pattern PATH_PATTERN = Pattern.compile(",");

}
