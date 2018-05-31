package org.janelia.alignment.spec.stack;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import mpicbg.models.AffineModel2D;
import mpicbg.models.TranslationModel2D;

import org.janelia.alignment.json.JsonUtils;
import org.janelia.alignment.match.CanvasId;
import org.janelia.alignment.match.MatchCollectionId;
import org.janelia.alignment.match.OrderedCanvasIdPair;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;

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
    private StackId parentTierStackId;
    private StackId alignedStackId;
    private StackId warpTilesStackId;
    private final Integer tier;
    private final Integer tierRow;
    private final Integer tierColumn;
    private final Integer totalTierRowCount;
    private final Integer totalTierColumnCount;
    private final Double scale;
    private final Bounds fullScaleBounds;
    private MatchCollectionId matchCollectionId;
    private Long savedMatchPairCount;
    private Set<String> splitGroupIds;
    private Double alignmentQuality;

    // no-arg constructor needed for JSON deserialization
    @SuppressWarnings("unused")
    protected HierarchicalStack() {
        this(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Constructs metadata from specified parameters and conventional naming patterns.
     *
     * @param  roughTilesStackId     identifies the source render stack with roughly aligned montage tiles.
     * @param  tierRow               row of this stack within its tier.
     * @param  tierColumn            column of this stack within its tier.
     * @param  totalTierRowCount     total number of rows in this stack's tier.
     * @param  totalTierColumnCount  total number of columns in this stack's tier.
     * @param  scale                 scale for rendering layers in this stack (and tier).
     * @param  fullScaleBounds       (rough tiles stack) world coordinate bounds for all layers in this stack.
     */
    public HierarchicalStack(final StackId roughTilesStackId,
                             final int tier,
                             final int tierRow,
                             final int tierColumn,
                             final int totalTierRowCount,
                             final int totalTierColumnCount,
                             final double scale,
                             final Bounds fullScaleBounds) {

        this(roughTilesStackId,
             null,
             null,
             null,
             tier,
             tierRow,
             tierColumn,
             totalTierRowCount,
             totalTierColumnCount,
             scale,
             fullScaleBounds,
             null);

        this.parentTierStackId = deriveParentTierStackId(roughTilesStackId, tier);

        final StackId splitStackId = this.getSplitStackId();

        this.alignedStackId = new StackId(splitStackId.getOwner(),
                                          splitStackId.getProject(),
                                          splitStackId.getStack() + "_align");

        this.warpTilesStackId = deriveWarpStackIdForTier(roughTilesStackId, tier);

        final String collectionName = splitStackId.getProject() + "_" + splitStackId.getStack();
        this.matchCollectionId = new MatchCollectionId(splitStackId.getOwner(), collectionName);
        this.splitGroupIds = null;
    }

    /**
     * Constructs metadata from explicitly specified values.
     *
     * @param  roughTilesStackId     identifies the source render stack with roughly aligned montage tiles.
     * @param  parentTierStackId     identifies the n-1 tier stack from which this stack was derived.
     * @param  warpTilesStackId      identifies warp tiles stack to which this stack's alignment results should be applied.
     * @param  tier                  tier for this stack.
     * @param  tierRow               row of this stack within its tier.
     * @param  tierColumn            column of this stack within its tier.
     * @param  totalTierRowCount     total number of rows in this stack's tier.
     * @param  totalTierColumnCount  total number of columns in this stack's tier.
     * @param  scale                 scale for rendering layers in this stack (and tier).
     * @param  fullScaleBounds       (rough tiles stack) world coordinate bounds for all layers in this stack.
     */
    public HierarchicalStack(final StackId roughTilesStackId,
                             final StackId parentTierStackId,
                             final StackId alignedStackId,
                             final StackId warpTilesStackId,
                             final Integer tier,
                             final Integer tierRow,
                             final Integer tierColumn,
                             final Integer totalTierRowCount,
                             final Integer totalTierColumnCount,
                             final Double scale,
                             final Bounds fullScaleBounds,
                             final MatchCollectionId matchCollectionId) {

        this.roughTilesStackId = roughTilesStackId;
        this.parentTierStackId = parentTierStackId;
        this.alignedStackId = alignedStackId;
        this.warpTilesStackId = warpTilesStackId;
        this.tier = tier;
        this.tierRow = tierRow;
        this.tierColumn = tierColumn;
        this.totalTierRowCount = totalTierRowCount;
        this.totalTierColumnCount = totalTierColumnCount;
        this.scale = scale;
        this.fullScaleBounds = fullScaleBounds;
        this.matchCollectionId = matchCollectionId;
        this.savedMatchPairCount = null;
        this.alignmentQuality = null;
    }

    public StackId getRoughTilesStackId() {
        return roughTilesStackId;
    }

    public StackId getParentTierStackId() {
        return parentTierStackId;
    }

    public StackId getAlignedStackId() {
        return alignedStackId;
    }

    public StackId getWarpTilesStackId() {
        return warpTilesStackId;
    }

    public Integer getTier() {
        return tier;
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
     * @return full scale pixel height for this stack's tier.
     */
    public double getTotalTierFullScaleHeight() {
        return fullScaleBounds.getDeltaY() * totalTierRowCount;
    }

    /**
     * @return total number of columns in this stack's tier.
     */
    public int getTotalTierColumnCount() {
        return totalTierColumnCount;
    }

    /**
     * @return full scale pixel width for this stack's tier.
     */
    public double getTotalTierFullScaleWidth() {
        return fullScaleBounds.getDeltaX() * totalTierColumnCount;
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

    public MatchCollectionId getMatchCollectionId() {
        return matchCollectionId;
    }

    public void updateDerivedData(final HierarchicalStack storedData) {
        if (storedData != null) {
            this.savedMatchPairCount = storedData.savedMatchPairCount;
            this.alignmentQuality = storedData.alignmentQuality;
        }
    }

    @JsonIgnore
    public boolean requiresMatchDerivation() {
        return (savedMatchPairCount == null);
    }

    @JsonIgnore
    public boolean hasMatchPairs() {
        return (savedMatchPairCount != null) && (savedMatchPairCount > 0);
    }

    public Long getSavedMatchPairCount() {
        return savedMatchPairCount;
    }

    public void setSavedMatchPairCount(final Long savedMatchPairCount) {
        this.savedMatchPairCount = savedMatchPairCount;
    }

    public boolean hasSplitGroupId(final String groupId) {
        return (splitGroupIds != null) && (splitGroupIds.contains(groupId));
    }

    public void setSplitGroupIds(final Collection<String> groupIds) {
        splitGroupIds = new LinkedHashSet<>(groupIds);
    }

    @JsonIgnore
    public boolean requiresAlignment() {
        return hasMatchPairs() && (alignmentQuality == null);
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
        return this.getSplitStackId().getStack();
    }

    /**
     * @return conventional id for this stack.
     */
    @JsonIgnore
    public StackId getSplitStackId() {
        final String project = deriveProjectForTier(roughTilesStackId, tier);
        final int splitStackIndex = deriveSplitStackIndex(tierRow, tierColumn, totalTierRowCount);
        final String stack = String.format("%04dx%04d_%06d",
                                           totalTierRowCount, totalTierColumnCount, splitStackIndex);
        return new StackId(roughTilesStackId.getOwner(), project, stack);
    }

    /**
     * @param  z  layer z value.
     *
     * @return conventional tile id for the specified layer in this stack.
     */
    @JsonIgnore
    public String getTileIdForZ(final double z) {
        return String.format("z_%2.1f_box_%d_%d_%d_%d_%f",
                             z,
                             floor(fullScaleBounds.getMinX()), floor(fullScaleBounds.getMinY()),
                             ceil(fullScaleBounds.getDeltaX()), ceil(fullScaleBounds.getDeltaY()),
                             scale);
    }

    /**
     * Sets the width, height, and bounding box for the specified tile spec based upon this stack's dimensions.
     *
     * @param  tileSpec  spec to update.
     */
    @JsonIgnore
    public void setTileSpecBounds(final TileSpec tileSpec) {
        // TODO: consider caching scaledCellBoundingBox rather than re-calculating it for each tile
        setTileSpecBounds(tileSpec, scale, fullScaleBounds);
    }

    /**
     * @param  z  layer z value.
     *
     * @return path that references parent tier box for the specified layer of this stack.
     */
    @JsonIgnore
    public String getBoxPathForZ(final double z) {
        return "/owner/" + parentTierStackId.getOwner() + "/project/" + parentTierStackId.getProject() +
               "/stack/" + parentTierStackId.getStack() + "/z/" + z + "/box/" +
               floor(fullScaleBounds.getMinX()) + ',' + floor(fullScaleBounds.getMinY()) + ',' +
               ceil(fullScaleBounds.getDeltaX()) + ',' + ceil(fullScaleBounds.getDeltaY()) + ',' + scale;
    }

    /**
     * Pairs each specified layer (z value) with the specified number of adjacent layers.
     *
     * @param  zValues            ordered list of z values for this stack.
     * @param  zNeighborDistance  number of adjacent layers to pair with each layer.
     *
     * @return list of layer pair identifiers.
     */
    @JsonIgnore
    public List<OrderedCanvasIdPair> getNeighborPairs(final List<Double> zValues,
                                                      final int zNeighborDistance) {

        final int n = zValues.size();
        final List<OrderedCanvasIdPair> neighborPairs = new ArrayList<>(n * zNeighborDistance);

        Double pz;
        Double qz;
        CanvasId p;
        CanvasId q;
        for (int i = 0; i < n; i++) {
            pz = zValues.get(i);
            p = new CanvasId(pz.toString(), getTileIdForZ(pz));
            for (int k = i + 1; k < n && k <= i + zNeighborDistance; k++) {
                qz = zValues.get(k);
                q = new CanvasId(qz.toString(), getTileIdForZ(qz));
                neighborPairs.add(new OrderedCanvasIdPair(p, q));
            }
        }

        return neighborPairs;
    }

    public String toJson() {
        return JSON_HELPER.toJson(this);
    }

    /**
     * Converts the specified affine to a full scale version that can be used in an
     * {@link org.janelia.alignment.transform.AffineWarpFieldTransform}.
     *
     * @param  alignedLayerTransformModel  scaled aligned model to convert.
     * @param  alignedStackScale           scale of the aligned stack.
     * @param  fullScaleStackBounds        full scale bounds of the stack prior to alignment.
     *
     * @return full scale relative version of the specified model.
     */
    @JsonIgnore
    public static AffineModel2D getFullScaleRelativeModel(final AffineModel2D alignedLayerTransformModel,
                                                          final double alignedStackScale,
                                                          final Bounds fullScaleStackBounds) {

        final AffineModel2D alignmentScaleModel = new AffineModel2D();
        alignmentScaleModel.set(alignedStackScale, 0.0, 0.0, alignedStackScale, 0.0, 0.0);

        final TranslationModel2D worldBoundsBoxMinTranslationModel = new TranslationModel2D();
        worldBoundsBoxMinTranslationModel.set(fullScaleStackBounds.getMinX(), fullScaleStackBounds.getMinY());

        final double fullScaleWidthDiv2 = fullScaleStackBounds.getDeltaX() / 2.0;
        final double fullScaleHeightDiv2 = fullScaleStackBounds.getDeltaY() / 2.0;
        final double scaledCenteredOnOriginX = alignedStackScale * fullScaleWidthDiv2;
        final double scaledCenteredOnOriginY = alignedStackScale * fullScaleHeightDiv2;

        final TranslationModel2D scaledCenterTranslationModel = new TranslationModel2D();
        scaledCenterTranslationModel.set(scaledCenteredOnOriginX, scaledCenteredOnOriginY);

        AffineModel2D fullScaleRelativeModel = null;
        switch (getFullScaleRelativeModelDerivationVersion()) {
            case 1: fullScaleRelativeModel = deriveV1(worldBoundsBoxMinTranslationModel, alignmentScaleModel, alignedLayerTransformModel, scaledCenterTranslationModel); break;
            case 2: fullScaleRelativeModel = deriveV2(worldBoundsBoxMinTranslationModel, alignmentScaleModel, alignedLayerTransformModel, scaledCenterTranslationModel); break;
            case 5: fullScaleRelativeModel = deriveV5(worldBoundsBoxMinTranslationModel, alignmentScaleModel, alignedLayerTransformModel, scaledCenterTranslationModel); break;
            case 6: fullScaleRelativeModel = deriveV6(worldBoundsBoxMinTranslationModel, alignmentScaleModel, alignedLayerTransformModel, scaledCenterTranslationModel); break;
        }
        return fullScaleRelativeModel;
    }

    private static AffineModel2D deriveV1(final TranslationModel2D worldBoundsBoxMinTranslationModel,
                                          final AffineModel2D alignmentScaleModel,
                                          final AffineModel2D alignedLayerTransformModel,
                                          final TranslationModel2D scaledCenterTranslationModel) {

        // Stephan's original suggestion - produces jump in aggregate

        final AffineModel2D fullScaleRelativeModel = new AffineModel2D();

        // T Si A S Ti Si Ci S
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel.createInverse() );
        fullScaleRelativeModel.concatenate( alignedLayerTransformModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel );
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel.createInverse() );
        fullScaleRelativeModel.concatenate( alignmentScaleModel.createInverse() );
        fullScaleRelativeModel.concatenate( scaledCenterTranslationModel.createInverse() );
        fullScaleRelativeModel.concatenate( alignmentScaleModel );

        return fullScaleRelativeModel;
    }

    private static AffineModel2D deriveV2(final TranslationModel2D worldBoundsBoxMinTranslationModel,
                                          final AffineModel2D alignmentScaleModel,
                                          final AffineModel2D alignedLayerTransformModel,
                                          final TranslationModel2D scaledCenterTranslationModel) {

        // use scaledCenterTranslationModel (C) instead of inverse - produces jump in opposite direction

        final AffineModel2D fullScaleRelativeModel = new AffineModel2D();

        // T Si A S Ti Si C S
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel.createInverse() );
        fullScaleRelativeModel.concatenate( alignedLayerTransformModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel );
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel.createInverse() );
        fullScaleRelativeModel.concatenate( alignmentScaleModel.createInverse() );
        fullScaleRelativeModel.concatenate( scaledCenterTranslationModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel );

        return fullScaleRelativeModel;
    }

    private static AffineModel2D deriveV5(final TranslationModel2D worldBoundsBoxMinTranslationModel,
                                          final AffineModel2D alignmentScaleModel,
                                          final AffineModel2D alignedLayerTransformModel,
                                          final TranslationModel2D scaledCenterTranslationModel) {

        // simplified version of v2 that produces same result

        final AffineModel2D fullScaleRelativeModel = new AffineModel2D();

        // T Si A C S Ti
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel.createInverse() );
        fullScaleRelativeModel.concatenate( alignedLayerTransformModel );
        fullScaleRelativeModel.concatenate( scaledCenterTranslationModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel );
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel.createInverse() );

        return fullScaleRelativeModel;
    }

    private static AffineModel2D deriveV6(final TranslationModel2D worldBoundsBoxMinTranslationModel,
                                          final AffineModel2D alignmentScaleModel,
                                          final AffineModel2D alignedLayerTransformModel,
                                          final TranslationModel2D scaledCenterTranslationModel) {

        // v5 with flipped center translate - looks good for aggregate, still compresses for consensus

        final AffineModel2D fullScaleRelativeModel = new AffineModel2D();

        // T Si C A S Ti
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel.createInverse() );
        fullScaleRelativeModel.concatenate( scaledCenterTranslationModel );
        fullScaleRelativeModel.concatenate( alignedLayerTransformModel );
        fullScaleRelativeModel.concatenate( alignmentScaleModel );
        fullScaleRelativeModel.concatenate( worldBoundsBoxMinTranslationModel.createInverse() );

        return fullScaleRelativeModel;
    }

    public static HierarchicalStack fromJson(final String json) {
        return JSON_HELPER.fromJson(json);
    }

    public static String deriveProjectForTier(final StackId roughTilesStackId,
                                              final int tier) {
        return roughTilesStackId.getProject() + "_" + roughTilesStackId.getStack() + "_tier_" + tier;
    }

    // TODO: remove derivation version debug hacks
    public static int getFullScaleRelativeModelDerivationVersion() {
        return 6;
    }

    public static StackId deriveWarpStackIdForTier(final StackId roughTilesStackId,
                                                   final int tier) {
        final String warpStack = roughTilesStackId.getStack() + "_tier_" + tier + "_warp_v" + getFullScaleRelativeModelDerivationVersion();
        return new StackId(roughTilesStackId.getOwner(),
                           roughTilesStackId.getProject(),
                           warpStack);
    }

    public static StackId deriveParentTierStackId(final StackId roughTilesStackId,
                                                  final int tier) {
        final StackId parentTierStackId;
        if (tier > 1) {
            parentTierStackId = deriveWarpStackIdForTier(roughTilesStackId, (tier - 1));
        } else {
            parentTierStackId = roughTilesStackId;
        }
        return parentTierStackId;
    }

    public static int deriveSplitStackIndex(final Integer tierRow,
                                            final Integer tierColumn,
                                            final Integer totalTierRowCount) {
        return (tierRow * totalTierRowCount) + tierColumn;
    }

    public static int deriveRowsAndColumnsForTier(final int tier) {
        final int[] primeCandidates = { 1, 3, 7, 17, 37, 79, 163, 331, 673, 1361, 2729, 5471, 10949, 21911 };
        final int rowsAndColumns;
        if (tier < primeCandidates.length) {
            rowsAndColumns = primeCandidates[tier];
        } else {
            final int extraTiers = tier - primeCandidates.length + 1;
            rowsAndColumns = (primeCandidates[primeCandidates.length - 1] * 2 * extraTiers) + 1;
        }
        return rowsAndColumns;
    }

    public static int ceilIntDivide(final int numerator,
                                    final int denominator) {
        return (int) Math.ceil((double) numerator / denominator);
    }

    public static List<HierarchicalStack> splitTier(final StackId roughTilesStackId,
                                                    final Bounds parentStackBounds,
                                                    final int maxPixelsPerDimension,
                                                    final int tier) {

        final List<HierarchicalStack> splitStacks = new ArrayList<>();

        final int parentWidth = (int) Math.ceil(parentStackBounds.getDeltaX());
        final int parentHeight = (int) Math.ceil(parentStackBounds.getDeltaY());
        final int rowsAndColumns = deriveRowsAndColumnsForTier(tier);
        final int maxDimension = Math.max(parentWidth, parentHeight);
        final int maxDimensionPerCell = ceilIntDivide(maxDimension, rowsAndColumns);

        final int cellWidth = ceilIntDivide(parentWidth, rowsAndColumns);
        final int cellHeight = ceilIntDivide(parentHeight, rowsAndColumns);

        final double scale = Math.min(1.0, (double) maxPixelsPerDimension / maxDimensionPerCell);

        int row = 0;
        int column;
        Bounds splitStackBounds;
        final int parentMinX = parentStackBounds.getMinX().intValue();
        final int parentMinY = parentStackBounds.getMinY().intValue();
        final double parentMinZ = parentStackBounds.getMinZ();
        final int parentMaxX = (int) Math.ceil(parentStackBounds.getMaxX());
        final int parentMaxY = (int) Math.ceil(parentStackBounds.getMaxY());
        final double parentMaxZ = parentStackBounds.getMaxZ();
        for (int y = parentMinY; y < parentMaxY; y += cellHeight) {
            column = 0;
            for (int x = parentMinX; x < parentMaxX; x += cellWidth) {
                splitStackBounds = new Bounds((double) x,             (double) y,              parentMinZ,
                                              (double) x + cellWidth, (double) y + cellHeight, parentMaxZ);
                splitStacks.add(new HierarchicalStack(roughTilesStackId,
                                                      tier,
                                                      row,
                                                      column,
                                                      rowsAndColumns,
                                                      rowsAndColumns,
                                                      scale,
                                                      splitStackBounds));
                column++;
            }
            row++;
        }

        return splitStacks;
    }

    public static void setTileSpecBounds(final TileSpec tileSpec,
                                         final double scale,
                                         final Bounds fullScaleBounds) {
        final int scaledCellWidth = (int) Math.ceil(scale * fullScaleBounds.getDeltaX());
        final int scaledCellHeight = (int) Math.ceil(scale * fullScaleBounds.getDeltaY());

        tileSpec.setWidth((double) scaledCellWidth);
        tileSpec.setHeight((double) scaledCellHeight);
        tileSpec.deriveBoundingBox(tileSpec.getMeshCellSize(), true);

    }

    public static int floor(final double value) {
        return (int) Math.floor(value);
    }

    public static int ceil(final double value) {
        return (int) Math.ceil(value);
    }

    public static final JsonUtils.Helper<HierarchicalStack> JSON_HELPER =
            new JsonUtils.Helper<>(HierarchicalStack.class);

}
