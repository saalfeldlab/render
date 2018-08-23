package org.janelia.alignment.spec.stack;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
    public void testBuildCenterAspectTierDimensionsList() {

        final Bounds roughStackBounds = new Bounds(50000.0, 40000.0, 1.0, 70000.0, 65000.0, 3.0);
        final int fullScalePixelsPerCell = 1024 * 1024;

        final List<TierDimensions> tierDimensionsList =
                TierDimensions.buildCenterAspectTierDimensionsList(roughStackBounds,
                                                                   fullScalePixelsPerCell);

        final Object[][] expectedTierValues = {
                // minX,  minY,  maxX,  maxY, rows, columns, scale
                { 50000, 40000, 70000, 65000,    1,       1, 0.04576 },  // tier 0
                { 49020, 38772, 70980, 66228,    3,       3, 0.125   },  // tier 1
                { 47190, 36484, 72810, 68516,    7,       7, 0.25    },  // tier 2
                { 49935, 39916, 70065, 65084,   11,      11, 0.5     },  // tier 3
                { 49477, 39344, 70522, 65656,   23,      23, 1.0     }   // tier 4
        };

        validateTiers(expectedTierValues, tierDimensionsList);
    }

    @Test
    public void testBuildPrimeTierDimensionsList() {

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
    public void testBuildUpperLeftTierDimensionsList() {

        final Bounds roughStackBounds = new Bounds(50000.0, 40000.0, 1.0, 70000.0, 65000.0, 3.0);

        final List<TierDimensions> tierDimensionsList =
                TierDimensions.buildUpperLeftTierDimensionsList(roughStackBounds, 1024, 1024);

        final Object[][] expectedTierValues = {
                // minX,  minY,  maxX,  maxY, rows, columns, scale
                { 50000, 40000, 70000, 65000,    1,       1, 0.04096 },  // tier 0
                { 50000, 40000, 70480, 68672,    7,       5, 0.25    },  // tier 1
                { 48976, 38976, 71504, 65600,   13,      11, 0.5     },  // tier 2
                { 49488, 39488, 70992, 65088,   25,      21, 1.0     }   // tier 3
        };

        validateTiers(expectedTierValues, tierDimensionsList);
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

        // minX, minY, maxX, maxY, cellWidth, cellHeight
        final List<String> defaultArgs = Arrays.asList("50000", "40000", "70000", "65000", "1024", "1024"); // tall
//        final List<String> defaultArgs = Arrays.asList("50000", "40000", "70000", "50000", "1024", "1024"); // wide

        final List<String> effectiveArgs = new ArrayList<>();
        if (args.length > 7) {
            effectiveArgs.addAll(Arrays.asList(args));
        } else {
            effectiveArgs.addAll(defaultArgs);
            effectiveArgs.add(LayerSplitMethod.UPPER_LEFT.toString()); // CENTER, CENTER_ASPECT, PRIME, UPPER_LEFT
        }

        final Bounds roughStackBounds = new Bounds(Double.parseDouble(effectiveArgs.get(0)),
                                                   Double.parseDouble(effectiveArgs.get(1)),
                                                   1.0,
                                                   Double.parseDouble(effectiveArgs.get(2)),
                                                   Double.parseDouble(effectiveArgs.get(3)),
                                                   3.0);
        final int cellWidth = Integer.parseInt(effectiveArgs.get(4));
        final int cellHeight = Integer.parseInt(effectiveArgs.get(5));

        final TierDimensions.LayerSplitMethod layerSplitMethod = LayerSplitMethod.valueOf(effectiveArgs.get(6));

        final List<TierDimensions> tierDimensionsList = TierDimensions.buildTierDimensionsList(layerSplitMethod,
                                                                                               roughStackBounds,
                                                                                               cellWidth,
                                                                                               cellHeight,
                                                                                               null);
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
        final BasicStroke highlightStroke = new BasicStroke(5);

        int highlightRow;
        int highlightColumn;

        for (int tier = 1; tier < tierDimensionsList.size(); tier++) {
            targetGraphics.setStroke(defaultStroke);
            targetGraphics.setColor(colorStream.getNextColor());
            tierDimensions = tierDimensionsList.get(tier);

            final int tierPixelsPerRow = (int) (tierDimensions.getFullScaleCellHeight() * tierZeroScale);
            final int tierPixelsPerColumn = (int) (tierDimensions.getFullScaleCellWidth() * tierZeroScale);

            if (LayerSplitMethod.UPPER_LEFT.equals(layerSplitMethod)) {
                highlightRow = tier == 1 ? 0 : (int) Math.pow(2, (tier - 2));
                highlightColumn = highlightRow;
            } else {
                highlightRow = tierDimensions.getRows() / 2;
                highlightColumn = tierDimensions.getColumns() / 2;
            }

            for (int row = 0; row < tierDimensions.getRows(); row++) {
                for (int column = 0; column < tierDimensions.getColumns(); column++) {

                    final Bounds splitStackBounds = tierDimensions.getCellBounds(row, column);
                    final int x = (int) ((splitStackBounds.getMinX() - minX) * tierZeroScale);
                    final int y = (int) ((splitStackBounds.getMinY() - minY) * tierZeroScale);

                    if ((row == highlightRow) && (column == highlightColumn)) {
                        targetGraphics.setStroke(highlightStroke);
                    } else {
                        targetGraphics.setStroke(defaultStroke);
                    }

                    targetGraphics.drawRect(x, y, tierPixelsPerColumn, tierPixelsPerRow);
                }
            }

        }

        targetGraphics.dispose();

        final String targetPath = effectiveArgs.size() == 8 ?
                                  effectiveArgs.get(7) :
                                  "tier_dimensions_" + String.join("_", effectiveArgs) + ".png";
        final File targetFile = new File(targetPath);
        try {
            Utils.saveImage(targetImage, targetFile, false, 0.85f);
        } catch (final IOException e) {
            e.printStackTrace();
        }

    }

}
