/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment.spec.stack;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.Bounds;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link HierarchicalTierRegions} class.
 *
 * @author Eric Trautman
 */
@SuppressWarnings("Convert2streamapi")
public class HierarchicalTierRegionsTest {

    @Test
    public void testGetIncompleteTierStacks() throws Exception {

        final int maxPixelsPerDimension = 1024;
        final double maxCompleteAlignmentQuality = 1.0;

        final StackId roughTilesStackId = new StackId("flyTEM", "trautmane_fafb_fold", "rough_tiles_e");
        final Bounds tier2WarpTilesBounds = new Bounds(36645.0, 37689.0, 2210.0,
                                                       48752.0,
                                                       50262.0, 2220.0);

        int tier = 3;
        final TierDimensions tier3Dimensions = TierDimensions.buildPrimeSplitTier(tier2WarpTilesBounds,
                                                                                  maxPixelsPerDimension,
                                                                                  tier);
        final List<HierarchicalStack> tier3Stacks = tier3Dimensions.getSplitStacks(roughTilesStackId,
                                                                                   tier);
        for (final HierarchicalStack splitStack : tier3Stacks) {
            splitStack.setAlignmentQuality(maxCompleteAlignmentQuality - 0.1);
        }

        final Bounds tier3WarpTilesBounds = new Bounds(36645.0, 37689.0, 2210.0,
                                                       48753.0, // one pixel more than tier 2
                                                       50262.0, 2220.0);

        HierarchicalTierRegions tierRegions = new HierarchicalTierRegions(tier3WarpTilesBounds,
                                                                          tier3Stacks,
                                                                          tier3Dimensions,
                                                                          maxCompleteAlignmentQuality);

        tier = 4;
        final TierDimensions tier4Dimensions = TierDimensions.buildPrimeSplitTier(tier3WarpTilesBounds,
                                                                                  maxPixelsPerDimension,
                                                                                  tier);
        final List<HierarchicalStack> tier4Stacks = tier4Dimensions.getSplitStacks(roughTilesStackId,
                                                                                   tier);

        List<HierarchicalStack> incompleteTier4Stacks = tierRegions.getIncompleteTierStacks(tier4Stacks);

        Assert.assertEquals("invalid number of incomplete stacks when all prior tier stacks have good quality",
                            0, incompleteTier4Stacks.size());

        for (final HierarchicalStack splitStack : tier3Stacks) {
            if (splitStack.getTierRow() == 0) {
                splitStack.setAlignmentQuality(maxCompleteAlignmentQuality + 0.1);
            }
        }

        tierRegions = new HierarchicalTierRegions(tier3WarpTilesBounds,
                                                  tier3Stacks,
                                                  tier3Dimensions,
                                                  maxCompleteAlignmentQuality);

        incompleteTier4Stacks = tierRegions.getIncompleteTierStacks(tier4Stacks);

        final int tier3Columns = tier3Stacks.get(0).getTotalTierColumnCount();
        final int tier4Columns = tier4Stacks.get(0).getTotalTierColumnCount();
        final int expectedIncompleteTier4Rows = (tier4Columns / tier3Columns) + 1;
        final int expectedIncompleteStacksSize = expectedIncompleteTier4Rows * tier4Columns;

        Assert.assertEquals("invalid number of incomplete stacks when first row prior tier stacks have bad quality",
                            expectedIncompleteStacksSize, incompleteTier4Stacks.size());

        final List<HierarchicalStack> partialTier3Stacks = new ArrayList<>(tier3Stacks.size());
        for (final HierarchicalStack splitStack : tier3Stacks) {
            if (splitStack.getTierRow() < 2) {
                partialTier3Stacks.add(splitStack);
            }
        }

        tierRegions = new HierarchicalTierRegions(tier3WarpTilesBounds,
                                                  partialTier3Stacks,
                                                  tier3Dimensions,
                                                  maxCompleteAlignmentQuality);

        incompleteTier4Stacks = tierRegions.getIncompleteTierStacks(tier4Stacks);

        Assert.assertEquals("same number of incomplete stacks should be found for partial list",
                            expectedIncompleteStacksSize, incompleteTier4Stacks.size());

    }

}
