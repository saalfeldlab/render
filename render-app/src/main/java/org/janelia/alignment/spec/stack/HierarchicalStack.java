package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.awt.geom.AffineTransform;
import java.io.Serializable;

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
    private final StackId parentTierStackId;
    private final StackId warpTilesStackId;
    private final Integer tierRow;
    private final Integer tierColumn;
    private final Integer totalTierRowCount;
    private final Integer totalTierColumnCount;
    private final Double scale;
    private final Bounds fullScaleBounds;
    private Double alignmentQuality;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    private HierarchicalStack() {
        this(null, null, null, null, null, null, null, null, null);
    }

    /**
     *
     * @param  roughTilesStackId     identifies the source render stack with roughly aligned montage tiles.
     * @param  parentTierStackId     identifies the n-1 tier stack from which this stack was derived.
     * @param  warpTilesStackId      identifies warp tiles stack to which this stack's alignment results should be applied.
     * @param  tierRow               row of this stack within its tier.
     * @param  tierColumn            column of this stack within its tier.
     * @param  totalTierRowCount     total number of rows in this stack's tier.
     * @param  totalTierColumnCount  total number of columns in this stack's tier.
     * @param  scale                 scale for rendering layers in this stack (and tier).
     * @param  fullScaleBounds       (rough tiles stack) world coordinate bounds for all layers in this stack.
     */
    public HierarchicalStack(final StackId roughTilesStackId,
                             final StackId parentTierStackId,
                             final StackId warpTilesStackId,
                             final Integer tierRow,
                             final Integer tierColumn,
                             final Integer totalTierRowCount,
                             final Integer totalTierColumnCount,
                             final Double scale,
                             final Bounds fullScaleBounds) {
        this.roughTilesStackId = roughTilesStackId;
        this.parentTierStackId = parentTierStackId;
        this.warpTilesStackId = warpTilesStackId;
        this.tierRow = tierRow;
        this.tierColumn = tierColumn;
        this.totalTierRowCount = totalTierRowCount;
        this.totalTierColumnCount = totalTierColumnCount;
        this.scale = scale;
        this.fullScaleBounds = fullScaleBounds;
        this.alignmentQuality = null;
    }

    public StackId getParentTierStackId() {
        return parentTierStackId;
    }

    public StackId getRoughTilesStackId() {
        return roughTilesStackId;
    }

    public StackId getWarpTilesStackId() {
        return warpTilesStackId;
    }

    /**
     * @return row of this stack within its tier.
     */
    public int getTierRow() {
        return tierRow;
    }

    /**
     * @return column of this stack within its tier.
     */
    public int getTierColumn() {
        return tierColumn;
    }

    /**
     * @return total number of rows in this stack's tier.
     */
    public int getTotalTierRowCount() {
        return totalTierRowCount;
    }

    /**
     * @return total number of columns in this stack's tier.
     */
    public int getTotalTierColumnCount() {
        return totalTierColumnCount;
    }

    /**
     * @return scale for rendering layers in this stack (and tier).
     */
    public double getScale() {
        return scale;
    }

    /**
     * @return (rough tiles stack) world coordinate bounds for all layers in this stack.
     */
    public Bounds getFullScaleBounds() {
        return fullScaleBounds;
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

    /**
     * @param  z  layer z value.
     *
     * @return "source" rough tiles box path for the specified layer of this stack.
     */
    @JsonIgnore
    public String getLayerBoxPath(final double z) {
        return "/owner/" + roughTilesStackId.getOwner() + "/project/" + roughTilesStackId.getProject() +
               "/stack/" + roughTilesStackId.getStack() + "/z/" + z +
               "/box/" +  fullScaleBounds.getRoundedMinX() + ',' + fullScaleBounds.getRoundedMinY() + ',' +
               fullScaleBounds.getRoundedDeltaX() + ',' + fullScaleBounds.getRoundedDeltaY() + ',' + scale;
    }

    @JsonIgnore
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

}
