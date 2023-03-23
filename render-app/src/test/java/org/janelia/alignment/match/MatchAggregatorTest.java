package org.janelia.alignment.match;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link MatchAggregator} class.
 *
 * @author Eric Trautman
 */
public class MatchAggregatorTest {

    @Test
    public void testAggregateWithinRadius() {

        // 10x10 w/step 100
        final CanvasMatches a = new CanvasMatches("a", "ap", "a", "aq",
                                                  buildMatches(0,
                                                               0,
                                                               10,
                                                               10,
                                                               100,
                                                               5));

        // 5x10 w/step 100
        final CanvasMatches b = new CanvasMatches("b", "bp", "b", "bq",
                                                  buildMatches(100,
                                                               100,
                                                               5,
                                                               10,
                                                               100,
                                                               5));

        final Integer originalMatchCountB = b.getMatchCount();

        // 10x8 w/step 10
        final CanvasMatches c = new CanvasMatches("c", "cp", "c", "cq",
                                                  buildMatches(1000,
                                                               1000,
                                                               10,
                                                               8,
                                                               10,
                                                               105));

        final List<CanvasMatches> pairs = new ArrayList<>();
        pairs.add(a);
        pairs.add(b);
        pairs.add(c);

        final int maxMatchesPerPair = 70;
        MatchAggregator.aggregateWithinRadius(pairs,
                                              maxMatchesPerPair,
                                              101);

        Assert.assertTrue("too many matches (" + a.getMatchCount() + ") for " + a,
                          a.getMatchCount() <= maxMatchesPerPair);
        Assert.assertEquals("match count for " + b + " should not have changed",
                            originalMatchCountB, b.getMatchCount());
        Assert.assertEquals("all matches for " + c + " should have been aggregated into one match",
                            Integer.valueOf(1), c.getMatchCount());
    }

    private Matches buildMatches(final int xStart,
                                 final int yStart,
                                 final int matchesPerRow,
                                 final int matchesPerColumn,
                                 final int step,
                                 final int qOffset) {
        
        final int matchCount = matchesPerRow * matchesPerColumn;
        final int maxY = yStart + (matchesPerRow * step);
        final int maxX = xStart + (matchesPerColumn * step);
        final double[][] p = new double[2][matchCount];
        final double[][] q = new double[2][matchCount];
        final double[] w = new double[matchCount];

        int matchIndex = 0;
        for (int y = yStart; y < maxY; y += step) {
            for (int x = xStart; x < maxX; x += step) {
                p[0][matchIndex] = x;
                p[1][matchIndex] = y;
                q[0][matchIndex] = x + qOffset;
                q[1][matchIndex] = y + qOffset;
                w[matchIndex] = 1.0;
                matchIndex++;
            }
        }
        return new Matches(p, q, w);
    }

}
