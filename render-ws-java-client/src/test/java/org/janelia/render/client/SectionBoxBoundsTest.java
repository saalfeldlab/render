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

    private int maxLevels;

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

        maxLevels = 0;

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
        validateGroupBounds(layerBounds, 1, numberOfRenderGroups,
                            new int[][] {{expectedFirstRow, expectedFirstColumn},
                                         {expectedLastRow, expectedLastColumn}},
                            new int[][] {{expectedFirstRow - 1, expectedFirstColumn},
                                         {expectedLastRow, expectedLastColumn + 1}});

        // 51 rows * 100 columns = 5100 boxes
        // divided into 7 groups:
        //   groups 1-4 should each have 729 boxes and
        //   groups 5-7 should each have 728 boxes
        numberOfRenderGroups = 7;

        // for each group, check: first box, middle box, and last box
        int[][][] includedRowsAndColumns = new int[][][] {
                { {14,  9}, {18, 50}, {21,  37} }, // group 1
                { {21, 38}, {25, 50}, {28,  66} }, // group 2
                { {28, 67}, {32, 50}, {35,  95} }, // group 3
                { {35, 96}, {40, 50}, {43,  24} }, // group 4
                { {43, 25}, {47, 50}, {50,  52} }, // group 5
                { {50, 53}, {54, 50}, {57,  80} }, // group 6
                { {57, 81}, {62, 50}, {64, 108} }  // group 7
        };

        int[][] includedData;
        int[][] excludedData;
        for (int g = 0; g < includedRowsAndColumns.length; g++) {
            includedData = includedRowsAndColumns[g];
            excludedData = includedRowsAndColumns[(g + 1) % includedRowsAndColumns.length];
            validateGroupBounds(layerBounds, (g + 1), numberOfRenderGroups,
                                includedData,
                                excludedData);
        }

        // 51 rows * 100 columns = 5100 boxes
        // divided into 5200 groups:
        //   groups 1-5100 should each have 1 box and
        //   groups 5101-5200 should not have any boxes
        numberOfRenderGroups = 5200;
        validateGroupBounds(layerBounds,    1, numberOfRenderGroups, new int[][] {{14,  9}}, new int[][] {{14, 10}});
        validateGroupBounds(layerBounds,  801, numberOfRenderGroups, new int[][] {{22,  9}}, new int[][] {{22, 10}});
        validateGroupBounds(layerBounds, 5100, numberOfRenderGroups, new int[][] {{64,108}}, new int[][] {{64,107}});
        validateGroupBounds(layerBounds, 5150, numberOfRenderGroups, new int[][] {{      }}, new int[][] {{80, 80}});

        // 3 levels => rowsAndColumnsPerArea of 8
        // firstAreaRow = 1 (14 / 8), firstAreaColumn =  1 (  9 / 8)
        // lastAreaRow  = 8 (64 / 8), lastAreaColumn  = 13 (108 / 8)
        // numberOfAreas = 104 (8 * 13)
        // divided into 3 groups:
        //   groups 1-2 should each have 35 areas and
        //   group 3 should have 34 areas
        maxLevels = 3;
        numberOfRenderGroups = 3;

        // for each group, check: first box, middle box, and last box
        includedRowsAndColumns = new int[][][] {
                { {14,  9}, {22, 50}, {31,  79} }, // group 1
                { {31, 80}, {40, 50}, {55,  47} }, // group 2
                { {55, 48}, {58, 50}, {64, 108} }, // group 3
        };

        for (int g = 0; g < includedRowsAndColumns.length; g++) {
            includedData = includedRowsAndColumns[g];
            excludedData = includedRowsAndColumns[(g + 1) % includedRowsAndColumns.length];
            validateGroupBounds(layerBounds, (g + 1), numberOfRenderGroups,
                                includedData,
                                excludedData);
        }

        // 3 levels => rowsAndColumnsPerArea of 8
        // firstAreaRow = 1 (14 / 8), firstAreaColumn =  1 (  9 / 8)
        // lastAreaRow  = 8 (64 / 8), lastAreaColumn  = 13 (108 / 8)
        // numberOfAreas = 104 (8 * 13)
        // divided into 200 groups:
        //   groups 1-104 should each have 1 area and
        //   groups 105-200 should not have any areas
        numberOfRenderGroups = 200;
        validateGroupBounds(layerBounds,   1, numberOfRenderGroups, new int[][] {{14,  9}}, new int[][] {{14, 19}});
        validateGroupBounds(layerBounds,  14, numberOfRenderGroups, new int[][] {{16,  9}}, new int[][] {{15,  9}});
        validateGroupBounds(layerBounds, 104, numberOfRenderGroups, new int[][] {{64,108}}, new int[][] {{63,108}});
        validateGroupBounds(layerBounds, 150, numberOfRenderGroups, new int[][] {{      }}, new int[][] {{80, 80}});
    }

    private void validateGroupBounds(final Bounds layerBounds,
                                     final int renderGroup,
                                     final int numberOfRenderGroups,
                                     final int[][] includedRowAndColumnValues,
                                     final int[][] excludedRowAndColumnValues) {
        final String context = "group " + renderGroup + " of " + numberOfRenderGroups;
        final SectionBoxBounds boxBounds = new SectionBoxBounds(z, boxSize, boxSize, layerBounds);
        boxBounds.setRenderGroup(renderGroup, numberOfRenderGroups, maxLevels);

        int row;
        int column;

        for (final int[] rowAndColumn : includedRowAndColumnValues) {
            if (rowAndColumn.length > 1) {
                row = rowAndColumn[0];
                column = rowAndColumn[1];
                Assert.assertTrue("row " + row + ", column " + column + " should be in " + context,
                                  boxBounds.isInRenderGroup(row, column));
            }
        }

        for (final int[] rowAndColumn : excludedRowAndColumnValues) {
            if (rowAndColumn.length > 1) {
                row = rowAndColumn[0];
                column = rowAndColumn[1];
                Assert.assertFalse("row " + row + ", column " + column + " should NOT be in " + context,
                                   boxBounds.isInRenderGroup(row, column));
            }
        }
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
