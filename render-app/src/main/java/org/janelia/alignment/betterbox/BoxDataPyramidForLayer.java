package org.janelia.alignment.betterbox;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulates information about the pyramid of mipmaps for a layer that has been divided
 * into boxes of some specified width and height.
 * It is intended to facilitate optimal distribution of the work required to render the layer.
 *
 * @author Eric Trautman
 */
public class BoxDataPyramidForLayer
        implements Serializable {

    private final double z;
    private final int maxLevel;
    final boolean excludeAlreadyRenderedBoxes;
    final String baseBoxPath;
    final String pathSuffix;

    private final List<BoxData> pyramidList;
    private final List<Integer> levelBoxCounts;

    /**
     * Constructs a pyramid for the specified layer.
     *
     * @param  z                            layer position.
     * @param  layerBounds                  bounding box for all tiles in the layer.
     * @param  boxWidth                     width of each mipmap box.
     * @param  boxHeight                    height of each mipmap box.
     * @param  tileBoundsList               list of bounding boxes for each tile in the layer.
     * @param  maxLevel                     maximum level to construct.
     * @param  excludeAlreadyRenderedBoxes  if true, already rendered boxes will be excluded from the pyramid.
     * @param  baseBoxPath                  base path for all rendered boxes
     *                                      (e.g. '/nrs/spc/rendered_boxes/spc/aibs_mm2_data/1024x1024').
     * @param  pathSuffix                   suffix for all rendered boxes (e.g. '.jpg').
     */
    public BoxDataPyramidForLayer(final double z,
                                  final Bounds layerBounds,
                                  final int boxWidth,
                                  final int boxHeight,
                                  final List<TileBounds> tileBoundsList,
                                  final int maxLevel,
                                  final boolean excludeAlreadyRenderedBoxes,
                                  final String baseBoxPath,
                                  final String pathSuffix) {

        // calculate row and column 'bounds', making sure starting values are even
        int firstRow = layerBounds.getMinY() > 0 ? (int) (layerBounds.getMinY() / boxHeight) : 0;
        if (firstRow % 2 == 1) {
            firstRow--;
        }
        final int lastRow = (int) (layerBounds.getMaxY() / boxHeight);

        int firstColumn = layerBounds.getMinX() > 0 ? (int) (layerBounds.getMinX() / boxWidth) : 0;
        if (firstColumn % 2 == 1) {
            firstColumn--;
        }
        final int lastColumn = (int) (layerBounds.getMaxX() / boxWidth);

        this.z = z;
        this.maxLevel = maxLevel;
        this.excludeAlreadyRenderedBoxes = excludeAlreadyRenderedBoxes;
        this.baseBoxPath = baseBoxPath;
        this.pathSuffix = pathSuffix;

        this.pyramidList = new ArrayList<>();
        this.levelBoxCounts = new ArrayList<>();

        buildPyramid(firstRow, lastRow,
                     firstColumn, lastColumn,
                     boxWidth, boxHeight,
                     tileBoundsList);

    }

    public double getZ() {
        return z;
    }

    /**
     * @return list of all boxes sorted by level and parent location.
     */
    public List<BoxData> getPyramidList() {
        return pyramidList;
    }

    /**
     * @return list of the total number of boxes in each mipmap level of this layer.
     *         The index of each element identifies the mipmap level,
     *         so element 0 contains the number of level zero boxes,
     *         element 1 contains the the number of level one boxes, etc.
     */
    public List<Integer> getLevelBoxCounts() {
        return levelBoxCounts;
    }

    /**
     * @return the total number of boxes for this layer.
     */
    public int getSize() {
        return pyramidList.size();
    }

    private void buildPyramid(final int firstRow,
                              final int lastRow,
                              final int firstColumn,
                              final int lastColumn,
                              final int boxWidth,
                              final int boxHeight,
                              final List<TileBounds> tileBoundsList) {

        LOG.info("buildPyramid: entry, z={}", z);

        List<BoxData> parentBoxes = new ArrayList<>();

        final TileBoundsRTree rTree = new TileBoundsRTree(z, tileBoundsList);

        int x;
        int y;

        List<TileBounds> boxTileBoundsList;
        BoxData levelZeroBox;
        BoxData levelOneBox;

        for (int loopRow = firstRow; loopRow <= lastRow; loopRow += 2) {
            for (int loopColumn = firstColumn; loopColumn <= lastColumn; loopColumn += 2) {

                levelOneBox = new BoxData(z,
                                          1,
                                          (loopRow / 2),
                                          (loopColumn / 2));

                for (int childRow = loopRow; childRow < (loopRow + 2); childRow++) {
                    for (int childColumn = loopColumn; childColumn < (loopColumn + 2); childColumn++) {

                        y = childRow * boxHeight;
                        x = childColumn * boxWidth;

                        boxTileBoundsList = rTree.findTilesInBox(x, y, (x + boxWidth), (y + boxHeight));

                        if (boxTileBoundsList.size() > 0) {

                            levelZeroBox = new BoxData(z, 0, childRow, childColumn);

                            if (boxNeedsToBeRendered(levelZeroBox)) {
                                levelZeroBox.setLayerLevelIndex(pyramidList.size());
                                pyramidList.add(levelZeroBox);
                            }

                            levelOneBox.addChild(levelZeroBox);
                        }
                    }

                }

                if (levelOneBox.hasChildren()) {
                    levelOneBox.updateSiblingCountForAllChildren();
                    parentBoxes.add(levelOneBox);
                }
            }
        }

        levelBoxCounts.add(pyramidList.size());

        while ((parentBoxes.size() > 0) && (parentBoxes.get(0).getLevel() < maxLevel)) {
            addParentBoxesForLevel(parentBoxes);
            parentBoxes = buildNextLevel(parentBoxes);
        }

        if (maxLevel > 0) {
            addParentBoxesForLevel(parentBoxes);
        }

        LOG.info("buildPyramid: exit, z={}, pyramidList.size={}, levelBoxCounts={}",
                 z, pyramidList.size(), levelBoxCounts);
    }

    private boolean boxNeedsToBeRendered(final BoxData boxData) {
        boolean renderBox = true;
        if (excludeAlreadyRenderedBoxes) {
            final File boxFile = boxData.getAbsoluteLevelFile(baseBoxPath, pathSuffix);
            renderBox = ! boxFile.exists();
        }
        return renderBox;
    }

    private void addParentBoxesForLevel(final List<BoxData> parentBoxes) {

        Collections.sort(parentBoxes); // sort by row and column to keep geographically close boxes together

        int layerLevelIndex = 0;

        for (final BoxData parentBox : parentBoxes) {

            if (boxNeedsToBeRendered(parentBox)) {
                parentBox.setLayerLevelIndex(layerLevelIndex);
                pyramidList.add(parentBox);
                layerLevelIndex++;
            }

        }

        levelBoxCounts.add(layerLevelIndex);
    }

    private List<BoxData> buildNextLevel(final List<BoxData> childBoxes) {

        final Map<BoxData, BoxData> parentBoxMap = new HashMap<>(childBoxes.size() * 2);

        BoxData parentBox;
        BoxData key;
        for (final BoxData child : childBoxes) {

            key = child.getParentBoxData();
            parentBox = parentBoxMap.get(key);

            if (parentBox == null) {
                parentBox = key;
                parentBoxMap.put(key, parentBox);
            }

            parentBox.addChild(child);
        }

        final List<BoxData> parentBoxes = new ArrayList<>(parentBoxMap.size());
        for (final BoxData box : parentBoxMap.values()) {
            box.updateSiblingCountForAllChildren();
            parentBoxes.add(box);
        }

        return parentBoxes;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BoxDataPyramidForLayer.class);

}
