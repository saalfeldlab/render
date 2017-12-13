package org.janelia.alignment.betterbox;

import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import mpicbg.trakem2.util.Downsampler;

/**
 * Cached pixels for the rendered components (child boxes) of a parent level box along with
 * methods to render the parent level box from in-memory child pixels or from child image files.
 *
 * @author Eric Trautman
 */
public class RenderedBoxParent {

    private final BoxData boxData;
    private final String baseBoxPath;
    private final String pathSuffix;
    private final RenderedBox[] children;
    private int childCount;

    /**
     * Base constructor.
     *
     * @param  boxData      box information for this parent.
     * @param  baseBoxPath  base path for all rendered boxes (e.g. [stack directory]/[tile width]x[tile height]).
     * @param  pathSuffix   suffix for all rendered boxes (e.g. '.jpg').
     */
    public RenderedBoxParent(final BoxData boxData,
                             final String baseBoxPath,
                             final String pathSuffix) {
        this.boxData = boxData;
        this.baseBoxPath = baseBoxPath;
        this.pathSuffix = pathSuffix;
        this.children = new RenderedBox[4];
        this.childCount = 0;
    }

    /**
     * @return the image file for this parent.
     */
    public File getBoxFile() {
        return boxData.getAbsoluteLevelFile(baseBoxPath, pathSuffix);
    }

    /**
     * @return true if this parent has at least one defined child box; otherwise false.
     */
    public boolean hasChildren() {
        return (childCount > 0);
    }


    /**
     * Adds the specified child information to this parent box.
     *
     * @param  renderedChild  rendered child.
     *
     * @param  index          index of the child within this parent
     *                        (0: upper left, 1: upper right, 2: lower left, 3: lower right).
     *
     * @throws IllegalStateException
     *   if a child already exists for the specified index.
     */
    public void setChild(final RenderedBox renderedChild,
                         final int index)
            throws IllegalStateException {

        if (children[index] != null) {
            throw new IllegalStateException("rendered child " + index + " already exists for parent box " + boxData);
        }

        children[index] = renderedChild;
        childCount++;
    }

    /**
     * Loads the pixel data for this box's children from disk.
     */
    public void loadChildren() {
        for (final BoxData childData : boxData.getChildren()) {
            final RenderedBox renderedChild = new RenderedBox(childData.getAbsoluteLevelFile(baseBoxPath, pathSuffix));
            setChild(renderedChild, childData.getParentIndex());
        }
    }

    /**
     * Builds this box's pixel data from it's children's pixel data.
     *
     * @param  boxWidth   width of this box (and its children boxes).
     * @param  boxHeight  height of this box (and its children boxes).
     *
     * @return pixel data for this box.
     *
     * @throws IOException
     *   if this box cannot be rendered.
     */
    public BufferedImage buildImage(final int boxWidth,
                                    final int boxHeight)
            throws IOException {

        final BufferedImage fourTileImage = new BufferedImage(boxWidth * 2,
                                                              boxHeight * 2,
                                                              BufferedImage.TYPE_INT_ARGB);

        final Graphics2D fourTileGraphics = fourTileImage.createGraphics();

        drawImage(children[0], 0, 0, fourTileGraphics);
        drawImage(children[1], boxWidth, 0, fourTileGraphics);
        drawImage(children[2], 0, boxHeight, fourTileGraphics);
        drawImage(children[3], boxWidth, boxHeight, fourTileGraphics);

        final ImagePlus fourTileImagePlus = new ImagePlus("", fourTileImage);

        fourTileGraphics.dispose();

        final ImageProcessor parentProcessor =
                Downsampler.downsampleImageProcessor(fourTileImagePlus.getProcessor());

        return parentProcessor.getBufferedImage();
    }

    private void drawImage(final RenderedBox renderedBox,
                           final int x,
                           final int y,
                           final Graphics2D fourTileGraphics) {
        if (renderedBox != null) {
            fourTileGraphics.drawImage(renderedBox.getImage(), x, y, null);
        }
    }
}
