package org.janelia.alignment.spec.stack;

import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.Bounds;

/**
 * Hierarchical alignment involves recursive partitioning and scaling of a roughly aligned stack of montage tiles.
 *
 * All layers of a roughly aligned stack are partitioned into rows and columns with each 'cell' becoming
 * its own stack in the next tier of stacks.  These stacks are then further partitioned into subsequent tiers
 * until a sufficient alignment is derived.
 *
 * A hierarchical stack model encapsulates information about one stack (or cell) in a given tier.
 *
 * @author Eric Trautman
 */
public class HierarchicalStack implements Serializable {

    private final StackId roughTilesStackId;
    private final HierarchicalStack parent;
    private final int row;
    private final int column;
    private final int rowCount;
    private final int columnCount;
    private final double scale;
    private final Bounds bounds;
    private final StackId stackId;
    private Double alignmentQuality;

    /**
     * Constructs a root tier stack.
     *
     * @param  roughTilesStackId      identifies the source render stack with roughly aligned montage tiles.
     *
     * @param  roughTilesStackBounds  bounds for the roughly aligned stack (and this root tier).
     *
     * @param  rootTierScale          scale for rendering root tier layer 'scapes'.
     *                                This must be small enough so that each layer is renderable.
     *                                Full scale is 1.0, half scale is 0.5, ....
     */
    public HierarchicalStack(final StackId roughTilesStackId,
                             final Bounds roughTilesStackBounds,
                             final double rootTierScale) {

        this.roughTilesStackId = roughTilesStackId;
        this.parent = null;
        this.row = 0;
        this.column = 0;
        this.rowCount = 1;
        this.columnCount = 1;

        this.scale = rootTierScale;

        this.bounds = roughTilesStackBounds;

        final String canvasProjectName =
                roughTilesStackId.getProject() + "_" + roughTilesStackId.getStack() + "_canvases";
        this.stackId = new StackId(roughTilesStackId.getOwner(), canvasProjectName, "A");

        this.alignmentQuality = null;
    }

    /**
     * @return row for this stack within its tier.
     */
    public int getRow() {
        return row;
    }

    /**
     * @return column for this stack within its tier.
     */
    public int getColumn() {
        return column;
    }

    /**
     * @return total number of rows in this stack's tier.
     */
    public int getRowCount() {
        return rowCount;
    }

    /**
     * @return total number of columns in this stack's tier.
     */
    public int getColumnCount() {
        return columnCount;
    }

    /**
     * @return scale for rendering layers in this stack.
     */
    public double getScale() {
        return scale;
    }

    /**
     * @return world coordinate bounds for all layers in this stack.
     */
    public Bounds getBounds() {
        return bounds;
    }

    /**
     * @return identifier for this stack.
     */
    public StackId getStackId() {
        return stackId;
    }

    /**
     * @return alignment quality metric for this stack (or null if no metric has been derived).
     */
    public Double getAlignmentQuality() {
        return alignmentQuality;
    }

    public void setAlignmentQuality(final Double alignmentQuality) {
        this.alignmentQuality = alignmentQuality;
    }

    @Override
    public String toString() {
        return stackId.toString();
    }

    /**
     * @param  z  layer z value.
     *
     * @return web service box path for the specified layer in this stack.
     */
    public String getLayerBoxPath(final double z) {
        return "/owner/" + roughTilesStackId.getOwner() + "/project/" + roughTilesStackId.getProject() +
               "/stack/" + roughTilesStackId.getStack() + "/z/" + z +
               "/box/" +  bounds.getRoundedMinX() + ',' + bounds.getRoundedMinY() + ',' +
               bounds.getRoundedDeltaX() + ',' + bounds.getRoundedDeltaY() + ',' + scale;
    }

    public AffineModel2D getRelativeModel(final AffineModel2D alignedLayerTransformModel,
                                          final double alignedFirstLayerTranslateX,
                                          final double alignedFirstLayerTranslateY) {

        final AffineTransform affine = new AffineTransform();
        affine.scale(1 / scale, 1 / scale);
        affine.translate(-alignedFirstLayerTranslateX, -alignedFirstLayerTranslateY);
        affine.concatenate(alignedLayerTransformModel.createAffine());
        affine.scale(scale, scale);
        final AffineModel2D model = new AffineModel2D();
        model.set(affine);
        return model;
    }

    /**
     * @return the root tier stack 'parent' of this stack.
     */
    public HierarchicalStack getRootTierStack() {
        HierarchicalStack stack = this;
        while (stack.parent != null) {
            stack = stack.parent;
        }
        return stack;
    }

    /**
     * Derives information for the next tier of child stacks by partitioning this stack
     * into the specified number of rows and columns.
     *
     * @param  rowAndColumnCount  number of rows and columns into which this stack should be divided.
     *
     * @return list of next tier child stacks resulting from partitioning.
     */
    public List<HierarchicalStack> partition(final int rowAndColumnCount) {

        final String stackName = getStackId().getStack();

        final int childTierRowCount = rowCount * rowAndColumnCount;
        final int childTierColumnCount = columnCount * rowAndColumnCount;
        final double childScale = scale * rowAndColumnCount;

        final HierarchicalStack rootStack = getRootTierStack();
        final int childColumnWidth = (int) ((rootStack.bounds.getDeltaX() / childTierColumnCount) + 0.5);
        final int childRowHeight = (int) ((rootStack.bounds.getDeltaY() / childTierRowCount) + 0.5);

        final int firstChildRow = row * rowAndColumnCount;
        final int lastChildRow = firstChildRow + rowAndColumnCount;
        final int firstChildColumn = column * rowAndColumnCount;
        final int lastChildColumn = firstChildColumn + rowAndColumnCount;

        final int childCount = rowAndColumnCount * rowAndColumnCount;
        final List<HierarchicalStack> children = new ArrayList<>(childCount);
        double childMinX;
        double childMinY;
        Bounds childBounds;
        String childStackName;
        int childIndex = 0;
        for (int childRow = firstChildRow; childRow < lastChildRow; childRow++) {

            childMinY = rootStack.bounds.getMinY() + (childRow * childRowHeight);

            for (int childColumn = firstChildColumn; childColumn < lastChildColumn; childColumn++) {

                childMinX = rootStack.bounds.getMinX() + (childColumn * childColumnWidth);
                childBounds = new Bounds(childMinX,
                                         childMinY,
                                         (childMinX + childColumnWidth),
                                         (childMinY + childRowHeight));

                childStackName = getTierStackName(stackName, childIndex, childCount);

                children.add(new HierarchicalStack(this,
                                                   childRow,
                                                   childColumn,
                                                   childTierRowCount,
                                                   childTierColumnCount,
                                                   childScale,
                                                   childBounds,
                                                   childStackName));
                childIndex++;
            }
        }

        return children;
    }

    private HierarchicalStack(final HierarchicalStack parent,
                              final int row,
                              final int column,
                              final int rowCount,
                              final int columnCount,
                              final double scale,
                              final Bounds bounds,
                              final String stackName) {

        this.roughTilesStackId = parent.roughTilesStackId;
        this.parent = parent;
        this.row = row;
        this.column = column;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.scale = scale;
        this.bounds = bounds;

        this.stackId = new StackId(parent.stackId.getOwner(), parent.stackId.getProject(), stackName);

        this.alignmentQuality = null;
    }

    private static String getTierStackName(final String parentStackName,
                                           final int childIndex,
                                           final int childCount) {

        final String tierStackName;
        if (childCount < 27) {
            tierStackName = String.valueOf((char) ('A' + childIndex));
        } else {
            final int pad = String.valueOf(childCount).length();
            final String format = "_%0" + pad + "d";
            tierStackName = String.format(format, childIndex);
        }

        return parentStackName + tierStackName;
    }

    public static void main(final String[] args) {

        final HierarchicalStack tier0 = new HierarchicalStack(new StackId("flyTEM", "trautmane_test", "rough_tiles"),
                                                              new Bounds(54954.0, 58314.0, 69539.0, 76856.0),
                                                              0.1);
        printInfo(tier0);
        for (final HierarchicalStack tier1 : tier0.partition(2)) {
            printInfo(tier1);
            for (final HierarchicalStack tier2 : tier1.partition(2)) {
                printInfo(tier2);
            }
        }

    }

    private static void printInfo(final HierarchicalStack stack) {
        System.out.println(stack.getStackId());
        System.out.println(stack.getLayerBoxPath(1));
        System.out.println();
    }
}
