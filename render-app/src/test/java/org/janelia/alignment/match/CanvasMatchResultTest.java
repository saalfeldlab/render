package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.junit.Assert;
import org.junit.Test;

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

        Assert.assertEquals("incorrect number of matches weights", originalList.size(), matches.getWs().length);

        final List<PointMatch> convertedList = CanvasMatchResult.convertMatchesToPointMatchList(matches);

        Assert.assertEquals("incorrect number of point matches", originalList.size(), convertedList.size());

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

        Assert.assertEquals("incorrect dimension size for " + context, expectedLocal.length, actualLocal.length);

        for (int i = 0; i < expectedLocal.length; i++) {
            Assert.assertEquals("incorrect value at index " + i + " of " + context,
                                expectedLocal[i], actualLocal[i], 0.0001);
        }

    }
}
