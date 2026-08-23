package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the {@link CanvasMatchResult} class.
 *
 * @author Eric Trautman
 */
public class CanvasMatchResultTest {

    @Test
    public void testConvertMethods() throws Exception {


        final List<PointMatch> originalList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            originalList.add(new PointMatch(new Point(new double[]{i,i*2}), new Point(new double[]{i*10,i*20}), i*0.1));
        }

        final Matches matches = CanvasMatchResult.convertPointMatchListToMatches(originalList, 1.0);

        assertEquals(originalList.size(), matches.getWs().length, "incorrect number of matches weights");

        final List<PointMatch> convertedList = CanvasMatchResult.convertMatchesToPointMatchList(matches);

        assertEquals(originalList.size(), convertedList.size(), "incorrect number of point matches");

        for (int i = 0; i < originalList.size(); i++) {
            verifyEquality("match " + i, originalList.get(i), convertedList.get(i));
        }

    }

    private void verifyEquality(final String context,
                                final PointMatch expected,
                                final PointMatch actual) {

        verifyEquality(context + " p1", expected.getP1(), actual.getP1());
        verifyEquality(context + " p2", expected.getP2(), actual.getP2());

    }

    private void verifyEquality(final String context,
                                final Point expected,
                                final Point actual) {

        final double[] expectedLocal = expected.getL();
        final double[] actualLocal = actual.getL();

        assertEquals(expectedLocal.length, actualLocal.length, "incorrect dimension size for " + context);

        for (int i = 0; i < expectedLocal.length; i++) {
            assertEquals(expectedLocal[i], actualLocal[i], 0.0001,
                         "incorrect value at index " + i + " of " + context);
        }

    }
}
