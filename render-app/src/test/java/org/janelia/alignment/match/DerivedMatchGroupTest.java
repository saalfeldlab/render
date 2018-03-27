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

import org.junit.Test;

import junit.framework.Assert;

/**
 * Tests the {@link DerivedMatchGroup} class.
 *
 * @author Eric Trautman
 */
public class DerivedMatchGroupTest {

    @Test
    public void testDerivePairs() throws Exception {

        final List<CanvasMatches> multipleGroupPairs = new ArrayList<>();
        multipleGroupPairs.add(buildTestMatches(1.0, 2.0, 0, new int[] {21, 22, 23, 24, 25, 81, 82, 83, 84, 85}));

        multipleGroupPairs.add(buildTestMatches(2.0, 3.0, 0, new int[] {11, 12, 13, 14, 15}));
        multipleGroupPairs.add(buildTestMatches(2.0, 3.0, 1, new int[] {91, 92, 93, 94, 95}));

        multipleGroupPairs.add(buildTestMatches(2.0, 4.0, 0, new int[] {7, 8}));
        multipleGroupPairs.add(buildTestMatches(2.0, 4.0, 1, new int[] {18, 19}));
        multipleGroupPairs.add(buildTestMatches(2.0, 4.0, 2, new int[] {87, 88}));
        multipleGroupPairs.add(buildTestMatches(2.0, 4.0, 3, new int[] {98, 99}));

        final DerivedMatchGroup derivedMatchGroup = new DerivedMatchGroup("2.0", multipleGroupPairs);

        final List<CanvasMatches> derivedPairs = derivedMatchGroup.getDerivedPairs();

        Assert.assertEquals("invalid number of derived match pairs",
                            10, derivedPairs.size());

    }

    private CanvasMatches buildTestMatches(final Double pZ,
                                           final Double qZ,
                                           final int indexWithinSet,
                                           final int[] xValues) {
        final int matchCount = xValues.length;
        final double[][] points = new double[2][matchCount];
        final double[] ws = new double[matchCount];
        final double y = 99.0;
        final int w = indexWithinSet % 2;

        for (int i = 0; i < matchCount; i++) {
            points[0][i] = xValues[i];
            points[1][i] = y;
            ws[i] = w;
        }

        final Matches matches = new Matches(points, points, ws);
        final String setSuffix = "_set_" + pZ + "_" + qZ + "_" + indexWithinSet;
        return new CanvasMatches(pZ.toString(), pZ + setSuffix, qZ.toString(), qZ + setSuffix, matches);
    }

}
