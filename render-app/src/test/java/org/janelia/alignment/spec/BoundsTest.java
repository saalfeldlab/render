package org.janelia.alignment.spec;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests the {@link Bounds} class.
 *
 * @author Eric Trautman
 */
public class BoundsTest {

    @Test
    public void testContainsInt() {

        final Bounds a = new Bounds(0.0, 0.0, 0.0, 10.0, 10.0, 10.0);

        final Bounds[] contained = {
                new Bounds(0.0, 0.0, 0.0, 10.0, 10.0, 10.0),
                new Bounds(0.0, 0.0, 0.0, 10.0, 10.0, 10.8),
                new Bounds(null, null, 2.0, null, null, 3.0),
        };
        for (final Bounds b : contained) {
            Assert.assertTrue(a + " should contain " + b, a.containsInt(b));
        }

        final Bounds[] outside = {
                new Bounds(0.0, 0.0, 0.0, 11.0, 10.0, 10.0),
                new Bounds(0.0, 0.0, 0.0, 1.0, 11.0, 1.0),
                new Bounds(null, null, -2.0, null, null, 3.0),
        };
        for (final Bounds b : outside) {
            Assert.assertFalse(a + " should NOT contain " + b, a.containsInt(b));
        }
    }


}
