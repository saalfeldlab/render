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
package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link CanvasFeatureMatchResult} class.
 *
 * @author Eric Trautman
 */
public class CanvasFeatureMatchResultTest {

    @Test
    public void testConvertMethods() throws Exception {


        final List<PointMatch> originalList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            originalList.add(new PointMatch(new Point(new double[]{i,i*2}), new Point(new double[]{i*10,i*20}), i*0.1));
        }

        final Matches matches = CanvasFeatureMatchResult.convertPointMatchListToMatches(originalList, 1.0);

        Assert.assertEquals("incorrect number of matches weights", originalList.size(), matches.getWs().length);

        final List<PointMatch> convertedList = CanvasFeatureMatchResult.convertMatchesToPointMatchList(matches);

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
