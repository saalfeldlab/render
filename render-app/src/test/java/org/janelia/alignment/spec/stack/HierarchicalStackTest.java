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

import mpicbg.trakem2.transform.AffineModel2D;

import org.janelia.alignment.spec.Bounds;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link HierarchicalStack} class.
 *
 * @author Eric Trautman
 */
public class HierarchicalStackTest {

    @Test
    public void testGetters() throws Exception {

        final StackId roughTilesStackId = new StackId("testOwner", "tilesProject", "roughTiles");
        final StackId parentTierStackId = new StackId("testOwner", "tilesProject_roughTiles_tiers", "tier_1");
        final StackId warpTilesStackId = new StackId("testOwner", "tilesProject_roughTiles_tiers", "tier_1_warp");
        final Integer tierRow = 1;
        final Integer tierColumn = 2;
        final Integer totalTierRowCount = 3;
        final Integer totalTierColumnCount = 4;
        final Double scale = 0.1;
        final Bounds fullScaleBounds = new Bounds(22.0, 33.0, 44.0, 55.0);

        final HierarchicalStack tier2Stack =
                new HierarchicalStack(roughTilesStackId,
                                      parentTierStackId,
                                      warpTilesStackId,
                                      tierRow,
                                      tierColumn,
                                      totalTierRowCount,
                                      totalTierColumnCount,
                                      scale,
                                      fullScaleBounds);

        Assert.assertEquals("invalid rough stack", roughTilesStackId.getStack(),
                            tier2Stack.getRoughTilesStackId().getStack());
        Assert.assertEquals("invalid parent stack", parentTierStackId.getStack(),
                            tier2Stack.getParentTierStackId().getStack());
        Assert.assertEquals("invalid warp stack", warpTilesStackId.getStack(),
                            tier2Stack.getWarpTilesStackId().getStack());
        Assert.assertEquals("invalid row",
                            tierRow.intValue(), tier2Stack.getTierRow());
        Assert.assertEquals("invalid column",
                            tierRow.intValue(), tier2Stack.getTierColumn());
        Assert.assertEquals("invalid row count",
                            totalTierRowCount.intValue(), tier2Stack.getTotalTierRowCount());
        Assert.assertEquals("invalid column count",
                            totalTierColumnCount.intValue(), tier2Stack.getTotalTierColumnCount());
        Assert.assertEquals("invalid scale",
                            scale, tier2Stack.getScale(), 0.0);
        Assert.assertEquals("invalid bounds",
                            fullScaleBounds.getDeltaX(), tier2Stack.getFullScaleBounds().getDeltaX(), 0.0);

        final Double quality = 66.0;
        tier2Stack.setAlignmentQuality(quality);
        Assert.assertEquals("invalid quality",
                            quality, tier2Stack.getAlignmentQuality(), 0.0);

        Assert.assertNotNull("invalid box path", tier2Stack.getLayerBoxPath(1.0));

        Assert.assertNotNull("invalid model", tier2Stack.getRelativeModel(new AffineModel2D(), 0.0, 0.0));

    }

}
