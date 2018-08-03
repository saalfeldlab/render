package org.janelia.alignment.spec.stack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileBounds;
import org.janelia.alignment.spec.TileBoundsRTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility to identify regions (hierarchical split stacks) in the current tier that need to be processed.
 * This is done by looking at the prior tier's alignment quality results and filtering out current
 * tier regions that already have sufficient alignment quality in the prior tier.
 *
 * @author Eric Trautman
 */
public class HierarchicalTierRegions {

    private final Bounds priorTierWarpStackBounds;
    private final TileBoundsRTree tierRegions;

    /**
     * Identifies prior tier regions with sufficient alignment quality.
     *
     * @param  priorTierWarpStackBounds     bounds of the prior tier warp stack.
     * @param  priorTierStacks              list of incomplete prior tier stacks.
     * @param  priorTierDimensions          dimensions for the prior tier.
     * @param  maxCompleteAlignmentQuality  tier stacks with quality values less than this maximum do not
     *                                      need to be aligned in subsequent tiers.
     */
    public HierarchicalTierRegions(final Bounds priorTierWarpStackBounds,
                                   final List<HierarchicalStack> priorTierStacks,
                                   final TierDimensions priorTierDimensions,
                                   final double maxCompleteAlignmentQuality) {
        this.priorTierWarpStackBounds = priorTierWarpStackBounds;
        this.tierRegions = new TileBoundsRTree(ZERO_Z, new ArrayList<>(priorTierStacks.size()));

        addPriorTierRegions(priorTierStacks, priorTierDimensions, maxCompleteAlignmentQuality);
    }

    /**
     * @return list of current tier stacks that do not have sufficient alignment quality in the prior tier.
     */
    @SuppressWarnings("Convert2streamapi")
    public List<HierarchicalStack> getIncompleteTierStacks(final List<HierarchicalStack> currentTierStacks) {

        final List<HierarchicalStack> incompleteTierStacks = new ArrayList<>(currentTierStacks.size());

        Integer currentTier = null;
        for (final HierarchicalStack splitStack : currentTierStacks) {
            if (currentTier == null) {
                currentTier = splitStack.getTier();
            }
            addTierRegion(splitStack, false);
        }

        final Set<String> completedSplitStackNames = new HashSet<>(currentTierStacks.size());
        for (final TileBounds completedSplitStack : tierRegions.findCompletelyObscuredTiles()) {
            completedSplitStackNames.add(completedSplitStack.getSectionId());
        }

        for (final HierarchicalStack currentTierStack : currentTierStacks) {
            if (! completedSplitStackNames.contains(currentTierStack.getSplitStackId().getStack())) {
                incompleteTierStacks.add(currentTierStack);
            }
        }

        LOG.info("getIncompleteTierStacks: exit, {} out of {} tier {} stacks are incomplete",
                 incompleteTierStacks.size(), currentTierStacks.size(), currentTier);

        return incompleteTierStacks;
    }

    private void addPriorTierRegions(final List<HierarchicalStack> existingPriorTierStacks,
                                     final TierDimensions priorTierDimensions,
                                     final double maxCompleteAlignmentQuality) {

        if (existingPriorTierStacks.size() > 0) {

            final HierarchicalStack firstStack = existingPriorTierStacks.get(0);
            final Integer priorTier = firstStack.getTier();

            final List<HierarchicalStack> allPriorTierStacks =
                    priorTierDimensions.getSplitStacks(firstStack.getRoughTilesStackId(),
                                                       priorTier);

            final Map<String, HierarchicalStack> existingNameToStackMap = new HashMap<>();
            for (final HierarchicalStack splitStack : existingPriorTierStacks) {
                existingNameToStackMap.put(splitStack.getSplitStackId().getStack(), splitStack);
            }

            Double alignmentQuality;
            HierarchicalStack existingStack;
            for (final HierarchicalStack splitStack : allPriorTierStacks) {

                existingStack = existingNameToStackMap.get(splitStack.getSplitStackId().getStack());

                if (existingStack != null) {
                    splitStack.updateDerivedData(existingStack);
                }

                alignmentQuality = splitStack.getAlignmentQuality();

                // TODO: this marks empty/failed areas as complete, is that okay?
                if ((alignmentQuality == null) || (alignmentQuality < maxCompleteAlignmentQuality)) {
                    addTierRegion(splitStack, true);
                }
            }

            LOG.info("addPriorTierRegions: exit, {} out of {} tier {} regions are complete (or unknown) based upon data for {} regions",
                     tierRegions.size(), allPriorTierStacks.size(), priorTier, existingPriorTierStacks.size());
        }

    }

    private void addTierRegion(final HierarchicalStack splitStack,
                               final boolean isPriorTier) {

        final Bounds bounds = splitStack.getFullScaleBounds();
        final String tileIdPrefix = isPriorTier ? PRIOR_TIER_PREFIX : CURRENT_TIER_PREFIX;
        final String regionTileId = tileIdPrefix + splitStack.getTileIdForZ(ZERO_Z);

        if (isPriorTier) {

            tierRegions.addTile(new TileBounds(regionTileId,
                                               splitStack.getSplitStackId().getStack(),
                                               ZERO_Z,
                                               bounds.getMinX(),
                                               bounds.getMinY(),
                                               bounds.getMaxX(),
                                               bounds.getMaxY()));

        } else if ((bounds.getMinX() < priorTierWarpStackBounds.getMaxX()) &&
                   (bounds.getMinY() < priorTierWarpStackBounds.getMaxY()) &&
                   (bounds.getMaxX() > priorTierWarpStackBounds.getMinX()) &&
                   (bounds.getMaxY() > priorTierWarpStackBounds.getMinY())) {

            tierRegions.addTile(new TileBounds(regionTileId,
                                               splitStack.getSplitStackId().getStack(),
                                               ZERO_Z,
                                               Math.max(bounds.getMinX(), priorTierWarpStackBounds.getMinX()),
                                               Math.max(bounds.getMinY(), priorTierWarpStackBounds.getMinY()),
                                               Math.min(bounds.getMaxX(), priorTierWarpStackBounds.getMaxX()),
                                               Math.min(bounds.getMaxY(), priorTierWarpStackBounds.getMaxY())));

        } else {

            LOG.info("addTierRegion: ignoring tier {} stack {} because its bounds {} are completely outside the prior tier bounds {}",
                     splitStack.getTier(), splitStack, bounds, priorTierWarpStackBounds);

        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalTierRegions.class);

    /**
     * These tier prefixes ensure current tier regions are "underneath" completed prior tier regions
     * because 'C' < 'P'.  This allows us to use {@link TileBoundsRTree#findCompletelyObscuredTiles()}
     * to easily find incomplete current tier regions.
     */
    private static final String CURRENT_TIER_PREFIX = "CURRENT_";
    private static final String PRIOR_TIER_PREFIX = "PRIOR_";

    private static final Double ZERO_Z = 0.0;

}
