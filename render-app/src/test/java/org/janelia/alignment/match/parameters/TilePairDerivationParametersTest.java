package org.janelia.alignment.match.parameters;

import java.util.ArrayList;
import java.util.List;

import org.janelia.alignment.spec.TileBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests the {@link TilePairDerivationParameters} class.
 */
public class TilePairDerivationParametersTest {

    @Test
    public void testShiftTileBoundsAsNeeded() {

        final TilePairDerivationParameters parameters = new TilePairDerivationParameters();

        final String testProject = "test_project";
        final String testStack = "test_stack";

        final double minX = 10.0;
        final double maxX = 20.0;
        final double minY = 100.0;
        final double maxY = 200.0;

        final List<TileBounds> tileBoundsList = new ArrayList<>();
        tileBoundsList.add(new TileBounds("A", "74.0", 74.0, minX, minY, maxX, maxY));
        tileBoundsList.add(new TileBounds("B", "75.0", 75.0, minX, minY, maxX, maxY));
        tileBoundsList.add(new TileBounds("C", "76.0", 76.0, minX, minY, maxX, maxY));

        final List<TileBounds> resultListA = parameters.shiftTileBoundsAsNeeded(testProject,
                                                                                testStack,
                                                                                tileBoundsList);
        assertSame(tileBoundsList, resultListA,
                   "original list not returned when layerShiftList is null");

        parameters.layerShiftList = new ArrayList<>();
        final List<TileBounds> resultListB = parameters.shiftTileBoundsAsNeeded(testProject,
                                                                                testStack,
                                                                                tileBoundsList);
        assertSame(tileBoundsList, resultListB,
                   "original list not returned when layerShiftList is empty");

        final double shiftX = 5.0;
        final double shiftY = 15.0;
        final LayerShift layerShift = new LayerShift(testProject, testStack, shiftX, shiftY, 76.0);
        parameters.layerShiftList.add(layerShift.toString());
        final List<TileBounds> resultListC = parameters.shiftTileBoundsAsNeeded(testProject,
                                                                                testStack,
                                                                                tileBoundsList);
        assertEquals(tileBoundsList.size(), resultListC.size(), "result list differs in size");
        assertSame(tileBoundsList.get(0), resultListC.get(0),
                   "first tile bounds should be the same");
        assertSame(tileBoundsList.get(1), resultListC.get(1),
                   "second tile bounds should be the same");

        final TileBounds thirdTileBounds = tileBoundsList.get(2);
        final TileBounds resultThirdTileBounds = resultListC.get(2);
        assertEquals(thirdTileBounds.getTileId(), resultThirdTileBounds.getTileId(),
                     "tileId for third tile bounds should be the same");
        assertEquals(thirdTileBounds.getZ(), resultThirdTileBounds.getZ(), 0.1,
                     "z for third tile bounds should be the same");
        assertEquals(thirdTileBounds.getMinX() + shiftX, resultThirdTileBounds.getMinX(), 0.1,
                     "incorrect shift for minX of third tile bounds");
        assertEquals(thirdTileBounds.getMaxX() + shiftX, resultThirdTileBounds.getMaxX(), 0.1,
                     "incorrect shift for maxX of third tile bounds");
        assertEquals(thirdTileBounds.getMinY() + shiftY, resultThirdTileBounds.getMinY(), 0.1,
                     "incorrect shift for minY of third tile bounds");
        assertEquals(thirdTileBounds.getMaxY() + shiftY, resultThirdTileBounds.getMaxY(), 0.1,
                     "incorrect shift for maxY of third tile bounds");
    }

}