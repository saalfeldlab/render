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
 * Tests the {@link HierarchicalStack} class.
 *
 * @author Eric Trautman
 */
public class HierarchicalStackTest {

    @Test
    public void testPartition() throws Exception {

        final StackId roughStackId = new StackId("test_owner", "test_project", "rough");
        final Bounds roughBounds = new Bounds(100.0, 200.0, 1100.0, 2200.0);
        final HierarchicalStack rootStack = new HierarchicalStack(roughStackId, roughBounds, 0.1);

        Assert.assertEquals(
                "invalid box path for root tier",
                "/owner/test_owner/project/test_project_canvas_rough/stack/A/z/1.0/box/100.0,200.0,1000.0,2000.0,0.1",
                rootStack.getLayerBoxPath(1));

        final List<HierarchicalStack> tier3StackList = new ArrayList<>();

        for (final HierarchicalStack tier1Stack : rootStack.partition(2)) {
            for (final HierarchicalStack tier2Stack : tier1Stack.partition(2)) {
                tier3StackList.addAll(tier2Stack.partition(2));
            }
        }

        Assert.assertEquals("invalid number of tier 3 stacks", 64, tier3StackList.size());

        //  index t0 t1 t2 t3 row col   minX   minY
        //      0  A  A  A  A   0   0    100    200       ***
        //      1           B   0   1    225    200
        //      2           C   1   0    100    450
        //      3           D   1   1    225    450
        //      4        B  A   0   2
        //      5           B   0   3
        //      6           C   1   2
        //      7           D   1   3    475    450       ***
        //      8        C  A   2   0    100    700       ***
        //      9           B   2   1
        //     10           C   3   0
        //     11           D   3   1
        //     12        D  A   2   2
        //     13           B   2   3
        //     14           C   3   2
        //     15           D   3   3
        //     16     B  A  A   0   4    600    200       ***
        //     17           B   0   5
        //     18           C   1   4
        //     19           D   1   5
        //    ...
        //     63  D  D  D  D   7   7    975   1950       ***

        validateTier3Stack(tier3StackList,  0, "AAAA", 0, 0,  100,  200);
        validateTier3Stack(tier3StackList,  7, "AABD", 1, 3,  475,  450);
        validateTier3Stack(tier3StackList,  8, "AACA", 2, 0,  100,  700);
        validateTier3Stack(tier3StackList, 16, "ABAA", 0, 4,  600,  200);
        validateTier3Stack(tier3StackList, 63, "ADDD", 7, 7,  975, 1950);

    }

    private void validateTier3Stack(final List<HierarchicalStack> stackList,
                                    final int index,
                                    final String expectedName,
                                    final int expectedRow,
                                    final int expectedColumn,
                                    final double expectedMinX,
                                    final double expectedMinY) {

        final String context = " for stack " + index;

        final HierarchicalStack stack = stackList.get(index);
        Assert.assertEquals("invalid name" + context, expectedName, stack.getStackId().getStack());

        Assert.assertEquals("invalid row" + context, expectedRow, stack.getRow());
        Assert.assertEquals("invalid column" + context, expectedColumn, stack.getColumn());

        Assert.assertEquals("invalid row count" + context, 8, stack.getRowCount());
        Assert.assertEquals("invalid column count" + context, 8, stack.getColumnCount());

        final Bounds bounds = stack.getBounds();
        Assert.assertEquals("invalid minX" + context, expectedMinX, bounds.getMinX(), 0.01);
        Assert.assertEquals("invalid minY" + context, expectedMinY, bounds.getMinY(), 0.01);

        final double quality = 1.2;
        stack.setAlignmentQuality(quality);
        Assert.assertEquals("invalid alignment quality" + context, quality, stack.getAlignmentQuality(), 0.01);
    }

}
