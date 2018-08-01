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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import org.janelia.alignment.Utils;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.util.DistinctColorStream;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link TierDimensions} class.
 *
 * @author Eric Trautman
 */
public class TierDimensionsTest {


    @Test
    public void testBuildTierDimensionsList() throws Exception {

        final Bounds roughStackBounds = new Bounds(50000.0, 40000.0, 1.0, 70000.0, 65000.0, 3.0);
        final int fullScalePixelsPerCell = 1024 * 1024;

        final List<TierDimensions> tierDimensionsList =
                TierDimensions.buildTierDimensionsList(roughStackBounds,
                                                       fullScalePixelsPerCell);

        final int[][] expectedTierValues = {
                // minX,  minY,  maxX,  maxY, rows, columns
                { 45360, 34196, 74640, 70804,    1,       1 },  // tier 0
                { 38040, 25044, 81960, 79956,    3,       3 },  // tier 1
                { 49020, 38772, 70980, 66228,    3,       3 },  // tier 2
                { 47190, 36484, 72810, 68516,    7,       7 },  // tier 3
                { 49935, 39916, 70065, 65084,   11,      11 },  // tier 4
                { 49477, 39344, 70522, 65656,   23,      23 }   // tier 5
        };

        Assert.assertEquals("invalid number of tiers", expectedTierValues.length, tierDimensionsList.size());

        for (int tier = 0; tier < tierDimensionsList.size(); tier++) {
            final TierDimensions tierDimensions = tierDimensionsList.get(tier);
            final Bounds tierBounds = tierDimensions.getFullScaleBounds();
            final String context = "invalid tier " + tier + " ";
            Assert.assertEquals(context + "minX",    expectedTierValues[tier][0], tierBounds.getMinX().intValue());
            Assert.assertEquals(context + "minY",    expectedTierValues[tier][1], tierBounds.getMinY().intValue());
            Assert.assertEquals(context + "maxX",    expectedTierValues[tier][2], tierBounds.getMaxX().intValue());
            Assert.assertEquals(context + "maxY",    expectedTierValues[tier][3], tierBounds.getMaxY().intValue());
            Assert.assertEquals(context + "rows",    expectedTierValues[tier][4], tierDimensions.getRows());
            Assert.assertEquals(context + "columns", expectedTierValues[tier][5], tierDimensions.getColumns());
        }

    }

    /**
     * Builds a tier dimensions list, draws its cells, and saves the results to a file for viewing.
     *
     * @param  args  (optional) input arguments: minX, minY, maxX, maxY, cellWidth, cellHeight, targetImagePath
     */
    public static void main(final String[] args) {

        final String defaultTargetPath = "tier_dimensions_" + new Date().getTime() + ".png";

        // args: minX, minY, maxX, maxY, cellWidth, cellHeight, targetImagePath
        final String[] effectiveArgs =
                (args.length > 6) ?
                args :
//                new String[] { "50000", "40000", "70000", "65000", "1024", "1024", defaultTargetPath }; // tall
                new String[] { "50000", "40000", "70000", "50000", "1024", "1024", defaultTargetPath }; // wide

        final Bounds roughStackBounds = new Bounds(Double.parseDouble(effectiveArgs[0]),
                                                   Double.parseDouble(effectiveArgs[1]),
                                                   1.0,
                                                   Double.parseDouble(effectiveArgs[2]),
                                                   Double.parseDouble(effectiveArgs[3]),
                                                   3.0);
        final int pixelsPerRow = Integer.parseInt(effectiveArgs[4]);
        final int pixelsPerColumn = Integer.parseInt(effectiveArgs[5]);

        final List<TierDimensions> tierDimensionsList =
                TierDimensions.buildTierDimensionsList(roughStackBounds,
                                                       pixelsPerRow,
                                                       pixelsPerColumn);

//        final List<TierDimensions> tierDimensionsList =
//                TierDimensions.buildTierDimensionsList(roughStackBounds,
//                                                       (pixelsPerRow * pixelsPerColumn));

        TierDimensions tierDimensions = tierDimensionsList.get(0);
        final Bounds tierZeroBounds = tierDimensions.getFullScaleBounds();
        final double tierZeroScale = tierDimensions.getScale();

        final int scaledWidth = (int) Math.ceil(tierZeroBounds.getDeltaX() * tierZeroScale);
        final int scaledHeight = (int) Math.ceil(tierZeroBounds.getDeltaY() * tierZeroScale);

        final BufferedImage targetImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D targetGraphics = targetImage.createGraphics();
        final DistinctColorStream colorStream = new DistinctColorStream();

        targetGraphics.setColor(Color.LIGHT_GRAY);

        final int scaledRoughX = (int) ((roughStackBounds.getMinX() - tierZeroBounds.getMinX()) * tierZeroScale);
        final int scaledRoughY = (int) ((roughStackBounds.getMinY() - tierZeroBounds.getMinY()) * tierZeroScale);
        final int scaledRoughWidth = (int) Math.ceil(roughStackBounds.getDeltaX() * tierZeroScale);
        final int scaledRoughHeight = (int) Math.ceil(roughStackBounds.getDeltaY() * tierZeroScale);

        targetGraphics.fillRect(scaledRoughX, scaledRoughY, scaledRoughWidth, scaledRoughHeight);

        final BasicStroke defaultStroke = new BasicStroke(1);
        final BasicStroke centerStroke = new BasicStroke(3);

        for (int tier = 1; tier < tierDimensionsList.size(); tier++) {
            targetGraphics.setStroke(defaultStroke);
            targetGraphics.setColor(colorStream.getNextColor());
            tierDimensions = tierDimensionsList.get(tier);

            final Bounds tierBounds = tierDimensions.getFullScaleBounds();
            final int offsetX = (int) ((tierBounds.getMinX() - tierZeroBounds.getMinX()) * tierZeroScale);
            final int offsetY = (int) ((tierBounds.getMinY() - tierZeroBounds.getMinY()) * tierZeroScale);
            final int tierPixelsPerRow =
                    (int) Math.ceil(tierDimensions.getFullScaleCellHeight() * tierZeroScale);
            final int tierPixelsPerColumn =
                    (int) Math.ceil(tierDimensions.getFullScaleCellWidth() * tierZeroScale);

            for (int row = 0; row < tierDimensions.getRows(); row++) {
                for (int column = 0; column < tierDimensions.getColumns(); column++) {
                    final int x = offsetX + (column * tierPixelsPerColumn);
                    final int y = offsetY + (row * tierPixelsPerRow);
                    targetGraphics.drawRect(x, y, tierPixelsPerColumn, tierPixelsPerRow);
                }
            }

            final int centerX = offsetX + ((int) Math.floor(tierDimensions.getColumns() / 2.0) * tierPixelsPerColumn);
            final int centerY = offsetY + ((int) Math.floor(tierDimensions.getRows() / 2.0) * tierPixelsPerRow);
            targetGraphics.setStroke(centerStroke);
            targetGraphics.drawRect(centerX, centerY, tierPixelsPerColumn, tierPixelsPerRow);

        }

        targetGraphics.dispose();

        final File targetFile = new File(effectiveArgs[6]);
        try {
            Utils.saveImage(targetImage, targetFile, false, 0.85f);
        } catch (final IOException e) {
            e.printStackTrace();
        }

    }


}
