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
import org.janelia.alignment.spec.stack.TierDimensions.LayerSplitMethod;
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
    public void testBuildCenterAspectTierDimensionsList() throws Exception {

        final Bounds roughStackBounds = new Bounds(50000.0, 40000.0, 1.0, 70000.0, 65000.0, 3.0);
        final int fullScalePixelsPerCell = 1024 * 1024;

        final List<TierDimensions> tierDimensionsList =
                TierDimensions.buildCenterAspectTierDimensionsList(roughStackBounds,
                                                                   fullScalePixelsPerCell);

        final Object[][] expectedTierValues = {
                // minX,  minY,  maxX,  maxY, rows, columns, scale
                { 45360, 34196, 74640, 70804,    1,       1, 0.03125 },  // tier 0
                { 38040, 25044, 81960, 79956,    3,       3, 0.0625  },  // tier 1
                { 49020, 38772, 70980, 66228,    3,       3, 0.125   },  // tier 2
                { 47190, 36484, 72810, 68516,    7,       7, 0.25    },  // tier 3
                { 49935, 39916, 70065, 65084,   11,      11, 0.5     },  // tier 4
                { 49477, 39344, 70522, 65656,   23,      23, 1.0     }   // tier 5
        };

        validateTiers(expectedTierValues, tierDimensionsList);
    }

    @Test
    public void testBuildPrimeTierDimensionsList() throws Exception {

        final Bounds roughStackBounds = new Bounds(50000.0, 40000.0, 1.0, 70000.0, 65000.0, 3.0);
        final int maxPixelsPerDimension = 1024;

        final List<TierDimensions> tierDimensionsList =
                TierDimensions.buildPrimeTierDimensionsList(roughStackBounds,
                                                            maxPixelsPerDimension,
                                                            4);

        final Object[][] expectedTierValues = {
                // minX,  minY,  maxX,  maxY, rows, columns,   scale
                { 50000, 40000, 70000, 65000,    1,       1, 0.04096 },  // tier 0
                { 50000, 40000, 70001, 65002,    3,       3, 0.12287 },  // tier 1
                { 50000, 40000, 70006, 65004,    7,       7, 0.28667 },  // tier 2
                { 50000, 40000, 70009, 65007,   17,      17, 0.69613 }   // tier 3
        };

        validateTiers(expectedTierValues, tierDimensionsList);
    }

    @Test
    public void testBuildPrimeSplitTier() throws Exception {

        final Bounds parentStackBounds = new Bounds(54954.0, 58314.0, 1.0, 69539.0, 76856.0, 3.0);
        final int maxPixesPerDimension = 4096;
        final Integer tier = 1;

        final TierDimensions tierDimensions = TierDimensions.buildPrimeSplitTier(parentStackBounds,
                                                                                 maxPixesPerDimension,
                                                                                 tier);

        final Object[] expectedTierValues = {
                // minX,  minY,  maxX,  maxY, rows, columns,   scale
                  54954, 58314, 69540, 76857,    3,       3, 0.66271
        };

        validateTier(expectedTierValues, tierDimensions, 1);
    }

    private void validateTiers(final Object[][] expectedTierValues,
                               final List<TierDimensions> tierDimensionsList) {

        Assert.assertEquals("invalid number of tiers", expectedTierValues.length, tierDimensionsList.size());

        for (int tier = 0; tier < tierDimensionsList.size(); tier++) {
            validateTier(expectedTierValues[tier], tierDimensionsList.get(tier), tier);
        }
    }

    private void validateTier(final Object[] expectedTierValues,
                              final TierDimensions tierDimensions,
                              final int tier) {

        final Bounds tierBounds = tierDimensions.getFullScaleBounds();
        final String context = "invalid tier " + tier + " ";
        Assert.assertEquals(context + "minX",    expectedTierValues[0], tierBounds.getMinX().intValue());
        Assert.assertEquals(context + "minY",    expectedTierValues[1], tierBounds.getMinY().intValue());
        Assert.assertEquals(context + "maxX",    expectedTierValues[2], tierBounds.getMaxX().intValue());
        Assert.assertEquals(context + "maxY",    expectedTierValues[3], tierBounds.getMaxY().intValue());
        Assert.assertEquals(context + "rows",    expectedTierValues[4], tierDimensions.getRows());
        Assert.assertEquals(context + "columns", expectedTierValues[5], tierDimensions.getColumns());
        Assert.assertEquals(context + "scale", (double) expectedTierValues[6], tierDimensions.getScale(), 0.0001);
    }

    /**
     * Builds a tier dimensions list, draws its cells, and saves the results to a file for viewing.
     *
     * @param  args  (optional) input arguments:
     *               minX, minY, maxX, maxY, cellWidth, cellHeight, splitMethod, targetImagePath
     */
    public static void main(final String[] args) {

        // args: minX, minY, maxX, maxY, cellWidth, cellHeight, targetImagePath
        final String[] effectiveArgs =
                (args.length > 7) ?
                args :
                new String[] { "50000", "40000", "70000", "65000", "1024", "1024", "CENTER" }; // tall
//                new String[] { "50000", "40000", "70000", "65000", "1024", "1024", "CENTER_ASPECT" }; // tall
//                new String[] { "50000", "40000", "70000", "50000", "1024", "1024", "CENTER" }; // wide
//                new String[] { "50000", "40000", "70000", "50000", "1024", "1024", "CENTER_ASPECT" }; // wide
//                new String[] { "50000", "40000", "70000", "50000", "1024", "1024", "PRIME" }; // wide

        final Bounds roughStackBounds = new Bounds(Double.parseDouble(effectiveArgs[0]),
                                                   Double.parseDouble(effectiveArgs[1]),
                                                   1.0,
                                                   Double.parseDouble(effectiveArgs[2]),
                                                   Double.parseDouble(effectiveArgs[3]),
                                                   3.0);
        final int cellWidth = Integer.parseInt(effectiveArgs[4]);
        final int cellHeight = Integer.parseInt(effectiveArgs[5]);

        final TierDimensions.LayerSplitMethod layerSplitMethod = LayerSplitMethod.valueOf(effectiveArgs[6]);

        final List<TierDimensions> tierDimensionsList;
        if (LayerSplitMethod.CENTER.equals(layerSplitMethod)) {
            tierDimensionsList = TierDimensions.buildCenterTierDimensionsList(roughStackBounds,
                                                                              cellWidth,
                                                                              cellHeight);
        } else if (TierDimensions.LayerSplitMethod.CENTER_ASPECT.equals(layerSplitMethod)) {
            tierDimensionsList = TierDimensions.buildCenterAspectTierDimensionsList(roughStackBounds,
                                                                                    (cellHeight * cellWidth));
        } else { // LayerSplitMethod.PRIME
            tierDimensionsList = TierDimensions.buildPrimeTierDimensionsList(roughStackBounds,
                                                                             cellHeight, // maxPixelsPerCell
                                                                             4);         // numberOfTiers
        }

        TierDimensions tierDimensions = tierDimensionsList.get(0);
        final Bounds tierZeroBounds = tierDimensions.getFullScaleBounds();
        final double tierZeroScale = tierDimensions.getScale();

        double minX = tierZeroBounds.getMinX();
        double minY = tierZeroBounds.getMinY();
        double maxX = tierZeroBounds.getMaxX();
        double maxY = tierZeroBounds.getMaxY();
        for (int tier = 1; tier < tierDimensionsList.size(); tier++) {
            final Bounds b = tierDimensionsList.get(tier).getFullScaleBounds();
            minX = Math.min(minX, b.getMinX());
            minY = Math.min(minY, b.getMinY());
            maxX = Math.max(maxX, b.getMaxX());
            maxY = Math.max(maxY, b.getMaxY());
        }

        final int scaledWidth = (int) Math.ceil((maxX - minX) * tierZeroScale);
        final int scaledHeight = (int) Math.ceil((maxY - minY) * tierZeroScale);

        final BufferedImage targetImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D targetGraphics = targetImage.createGraphics();
        final DistinctColorStream colorStream = new DistinctColorStream();

        targetGraphics.setColor(Color.LIGHT_GRAY);

        final int scaledRoughX = (int) ((roughStackBounds.getMinX() - minX) * tierZeroScale);
        final int scaledRoughY = (int) ((roughStackBounds.getMinY() - minY) * tierZeroScale);
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
            final int offsetX = (int) ((tierBounds.getMinX() - minX) * tierZeroScale);
            final int offsetY = (int) ((tierBounds.getMinY() - minY) * tierZeroScale);
            final int tierPixelsPerRow = (int) (tierDimensions.getFullScaleCellHeight() * tierZeroScale);
            final int tierPixelsPerColumn = (int) (tierDimensions.getFullScaleCellWidth() * tierZeroScale);

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

        final String targetPath = effectiveArgs.length == 8 ?
                                  effectiveArgs[7] :
                                  "tier_dimensions_" + layerSplitMethod + "_" + new Date().getTime() + ".png";
        final File targetFile = new File(targetPath);
        try {
            Utils.saveImage(targetImage, targetFile, false, 0.85f);
        } catch (final IOException e) {
            e.printStackTrace();
        }

    }

}
