package org.janelia.render.client;

import org.janelia.alignment.spec.Bounds;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link SectionBoxBounds} class.
 *
 * @author Eric Trautman
 */
public class SectionBoxBoundsTest {

    private final Double z = 99.0;
    private int boxSize;
    private int expectedFirstColumn;
    private int expectedLastColumn;
    private int expectedFirstRow;
    private int expectedLastRow;
    private int expectedFirstX;
    private int expectedLastX;
    private int expectedFirstY;
    private int expectedLastY;

    @Test
    public void testBoxBounds() throws Exception {

        boxSize = 15;
        Bounds layerBounds = new Bounds(0.0, 0.0, 100.0, 100.0);

        expectedFirstColumn = 0;
        expectedLastColumn = 6;
        expectedFirstRow = 0;
        expectedLastRow = 6;
        expectedFirstX = 0;
        expectedLastX = 104;
        expectedFirstY = 0;
        expectedLastY = 104;

        SectionBoxBounds boxBounds = new SectionBoxBounds(z, boxSize, boxSize, layerBounds);
        validateBoxBounds("simple box, no groups, ", boxBounds);

        boxSize = 2048;
        layerBounds = new Bounds(19174.0, 30615.0, 222080.0, 132863.0);

        expectedFirstColumn = 9;
        expectedLastColumn = 108;
        expectedFirstRow = 14;
        expectedLastRow = 64;
        expectedFirstX = 18432;
        expectedLastX = 223231;
        expectedFirstY = 28672;
        expectedLastY = 133119;

        boxBounds = new SectionBoxBounds(z, boxSize, boxSize, layerBounds);
        validateBoxBounds("no groups, ", boxBounds);

        Integer numberOfRenderGroups = 1;
        // one group should be the same as no groups
        validateGroupBounds(layerBounds, 1, numberOfRenderGroups, new int[] {0, 5099}, new int[] {5100});

        // 51 rows * 100 columns = 5100 boxes
        // divided into 7 groups:
        //   groups 1-4 should each have 729 boxes and
        //   groups 5-7 should each have 728 boxes
        numberOfRenderGroups = 7;

        // groups 1-4 should each have 729 boxes
        int boxesPerGroup = 729;
        int firstValidBox = 0;
        int lastValidBox;
        for (int g = 1; g < 5; g++) {
            lastValidBox = firstValidBox + boxesPerGroup - 1;
            validateGroupBounds(layerBounds, g, numberOfRenderGroups,
                                new int[] {firstValidBox, lastValidBox},
                                new int[] {firstValidBox - 1, lastValidBox + 1});
            firstValidBox = lastValidBox + 1;
        }

        // groups 5-7 should each have 728 boxes
        boxesPerGroup = 728;
        for (int g = 5; g < 8; g++) {
            lastValidBox = firstValidBox + boxesPerGroup - 1;
            validateGroupBounds(layerBounds, g, numberOfRenderGroups,
                                new int[] {firstValidBox, lastValidBox},
                                new int[] {firstValidBox - 1, lastValidBox + 1});
            firstValidBox = lastValidBox + 1;
        }

        // 51 rows * 100 columns = 5100 boxes
        // divided into 5200 groups:
        //   groups 1-5100 should each have 1 box and
        //   groups 5101-5200 should not have any boxes
        numberOfRenderGroups = 5200;
        validateGroupBounds(layerBounds, 1, numberOfRenderGroups, new int[] {0}, new int[] {1});
        validateGroupBounds(layerBounds, 999, numberOfRenderGroups, new int[] {998}, new int[] {997});
        validateGroupBounds(layerBounds, 5100, numberOfRenderGroups, new int[] {5099}, new int[] {5100});
        validateGroupBounds(layerBounds, 5150, numberOfRenderGroups, new int[] {}, new int[] {5149});
    }

    private void validateGroupBounds(final Bounds layerBounds,
                                     final int renderGroup,
                                     final int numberOfRenderGroups,
                                     final int[] includedBoxes,
                                     final int[] excludedBoxes) {
        final String context = "group " + renderGroup + " of " + numberOfRenderGroups;
        final SectionBoxBounds boxBounds = new SectionBoxBounds(z, boxSize, boxSize, layerBounds);
        boxBounds.setRenderGroup(renderGroup, numberOfRenderGroups);
        for (final int boxNumber : includedBoxes) {
            validateIsInRenderGroup(context, boxBounds, boxNumber, true);
        }
        for (final int boxNumber : excludedBoxes) {
            validateIsInRenderGroup(context, boxBounds, boxNumber, false);
        }
    }

    private void validateIsInRenderGroup(final String context,
                                         final SectionBoxBounds boxBounds,
                                         final int boxNumber,
                                         final boolean expectedResult) {
        final int row = (boxNumber / boxBounds.getNumberOfColumns()) + boxBounds.getFirstRow();
        final int column = (boxNumber % boxBounds.getNumberOfColumns()) + boxBounds.getFirstColumn();
        Assert.assertEquals("invalid result for box " + boxNumber + " in " + context,
                            expectedResult, boxBounds.isInRenderGroup(row, column));
    }

    private void validateBoxBounds(final String context,
                                   final SectionBoxBounds boxBounds) {

        Assert.assertEquals(context + "invalid z", z.intValue(), boxBounds.getZ());

        Assert.assertEquals(context + "invalid first column", expectedFirstColumn, boxBounds.getFirstColumn());
        Assert.assertEquals(context + "invalid last column", expectedLastColumn, boxBounds.getLastColumn());
        Assert.assertEquals(context + "invalid first row", expectedFirstRow, boxBounds.getFirstRow());
        Assert.assertEquals(context + "invalid last row", expectedLastRow, boxBounds.getLastRow());

        Assert.assertEquals(context + "invalid first x", expectedFirstX, boxBounds.getFirstX());
        Assert.assertEquals(context + "invalid last x", expectedLastX, boxBounds.getLastX());
        Assert.assertEquals(context + "invalid first y", expectedFirstY, boxBounds.getFirstY());
        Assert.assertEquals(context + "invalid last y", expectedLastY, boxBounds.getLastY());
    }
}
