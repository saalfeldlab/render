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
package org.janelia.alignment.transform;

import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link AffineWarpField} class.
 *
 * @author Eric Trautman
 */
public class AffineWarpFieldTest {

    @Test
    public void testGetHighResolutionCopy() throws Exception {

        final int rowCount = 3;
        final int columnCount = 4;
        final AffineWarpField warpField =
                new AffineWarpField(1000, 1000, rowCount, columnCount, AffineWarpField.getDefaultInterpolatorFactory());

        for (int row = 0; row < rowCount; row++) {
            for (int column = 0; column < columnCount; column++) {
                final double v = (row * columnCount) + column;
                warpField.set(row, column, new double[] {v, v, v, v, v, v});
            }
        }

        final double[] originalValues = warpField.getValues();
        int expectedNumberOfValues = rowCount * columnCount * 6;

        Assert.assertEquals("invalid number of original values",
                            expectedNumberOfValues, originalValues.length);

        final int divideRowsBy = 3;
        final int divideColumnsBy = 3;
        final AffineWarpField hiResWarpField = warpField.getHighResolutionCopy(divideRowsBy, divideColumnsBy);

        final double[] hiResValues = hiResWarpField.getValues();
        expectedNumberOfValues = rowCount * divideRowsBy * columnCount * divideColumnsBy * 6;

        Assert.assertEquals("invalid number of high resolution values",
                            expectedNumberOfValues, hiResValues.length);

        final int originalRow = 1;
        final int originalColumn = 2;
        final double[] originalCellValues = warpField.get(originalRow, originalColumn);

        final int hiResRow = 4;
        final int hiResColumn = 6;
        final double[] hiResCellValues = hiResWarpField.get(hiResRow, hiResColumn);

        for (int i = 0; i < originalCellValues.length; i++) {
            Assert.assertEquals("invalid high resolution value for index " + i,
                                originalCellValues[i], hiResCellValues[i], 0.0001);
        }

    }


}
